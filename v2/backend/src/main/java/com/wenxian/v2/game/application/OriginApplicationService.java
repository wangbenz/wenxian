package com.wenxian.v2.game.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.ModelGateway;
import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.domain.Race;
import com.wenxian.v2.game.persistence.OriginProposalRepository;
import com.wenxian.v2.game.persistence.OriginProposalRepository.StoredProposal;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class OriginApplicationService {
    private static final String SYSTEM_PROMPT = """
            你是修仙单机文字游戏《问仙录》的创角命格生成器。
            世界固定为绝天之变后三千年的玄墟界青岐州。请严格遵守用户给定的姓名、性别、种族、本体根源与人生所求。
            生成三份不同的凡俗或初醒起点，只改变公开身份、生活处境、未偿旧债与开场危机。
            不赠予高阶修为、神器、前世大能身份、无代价血脉或必胜能力；妖族不得写成人族，灵族本体不得改写。
            三份候选都要能立刻开始游玩，并保留自由行动空间。只输出JSON，不输出Markdown或解释：
            {"candidates":[{"title":"2至8字命格名","publicIdentity":"当前公开身份","openingSituation":"80至180字开场处境","unpaidDebt":"未偿旧债或必须面对的牵连"}]}
            candidates必须恰好三项，文字使用简体中文。
            """;

    private final ModelGateway models;
    private final OriginProposalRepository proposals;
    private final ObjectMapper json;

    public OriginApplicationService(ModelGateway models, OriginProposalRepository proposals, ObjectMapper json) {
        this.models = models;
        this.proposals = proposals;
        this.json = json;
    }

    public OriginProposal generate(String userId, OriginRequest request) {
        validate(request);
        String fingerprint = fingerprint(request);
        String operationId = "v2-origin-" + UUID.randomUUID();
        ModelResult result = models.completeJson(new ModelRequest("STORY_PLANNING", userId, null, operationId,
                SYSTEM_PROMPT, userPrompt(request), 1800, 0.75));
        List<FateCandidate> candidates = parseCandidates(result.content());
        String proposalId = "v2-origin-proposal-" + UUID.randomUUID();
        Instant now = Instant.now();
        try {
            proposals.insert(proposalId, userId, fingerprint, json.writeValueAsString(candidates),
                    now.plus(Duration.ofMinutes(30)), now);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to store origin proposal", error);
        }
        return new OriginProposal(proposalId, now.plus(Duration.ofMinutes(30)), candidates);
    }

    public FateCandidate consume(String userId, String proposalId, String candidateId,
                                 OriginRequest request, Instant now) {
        if (proposalId == null || proposalId.isBlank() || candidateId == null || candidateId.isBlank()) {
            return fallback(request);
        }
        StoredProposal stored = proposals.lock(proposalId.strip(), userId);
        if (stored == null || stored.consumedAt() != null || stored.expiresAt().isBefore(now)
                || !stored.fingerprint().equals(fingerprint(request))) {
            throw V2ApiException.conflict("ORIGIN_PROPOSAL_EXPIRED",
                    "这次命格推演已经失效，命卷尚未创建。", "重新推演三份命格后再选择");
        }
        List<FateCandidate> candidates;
        try {
            candidates = json.readerForListOf(FateCandidate.class).readValue(stored.candidatesJson());
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read stored origin proposal", error);
        }
        FateCandidate selected = candidates.stream().filter(value -> value.id().equals(candidateId)).findFirst()
                .orElseThrow(() -> V2ApiException.badRequest("ORIGIN_CANDIDATE_INVALID", "所选命格不属于本次推演。"));
        proposals.consume(proposalId.strip(), userId, now);
        return selected;
    }

    private List<FateCandidate> parseCandidates(String raw) {
        try {
            String clean = raw == null ? "" : raw.strip();
            if (clean.startsWith("```")) {
                int firstBreak = clean.indexOf('\n');
                int lastFence = clean.lastIndexOf("```");
                clean = firstBreak >= 0 && lastFence > firstBreak ? clean.substring(firstBreak + 1, lastFence).strip() : clean;
            }
            JsonNode values = json.readTree(clean).path("candidates");
            if (!values.isArray() || values.size() != 3) throw new IllegalArgumentException("candidate count");
            List<FateCandidate> result = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                JsonNode value = values.get(index);
                result.add(new FateCandidate("fate-" + (index + 1),
                        text(value, "title", 2, 20), text(value, "publicIdentity", 2, 80),
                        text(value, "openingSituation", 20, 300), text(value, "unpaidDebt", 2, 160)));
            }
            return List.copyOf(result);
        } catch (Exception error) {
            throw V2ApiException.conflict("ORIGIN_GENERATION_INVALID",
                    "命格尚未推演完整，角色资料没有被修改。", "保留当前设定并重新推演");
        }
    }

    private String text(JsonNode node, String field, int min, int max) {
        String value = node.path(field).asText("").strip();
        if (value.length() < min || value.length() > max) throw new IllegalArgumentException(field);
        return value;
    }

    private void validate(OriginRequest request) {
        if (request == null) throw V2ApiException.badRequest("ORIGIN_REQUEST_REQUIRED", "缺少角色根源信息。");
        Race.parse(request.race());
        require(request.name(), 1, 20, "请先写下角色姓名");
        require(request.genderExpression(), 1, 20, "请先选择性别表达");
        require(request.rootType(), 1, 40, "请先选择根源类型");
        require(request.rootDescription(), 2, 300, "请补充角色根源");
        require(request.lifeGoal(), 2, 160, "请写下此生所求");
    }

    private void require(String value, int min, int max, String message) {
        if (value == null || value.strip().length() < min || value.strip().length() > max) {
            throw V2ApiException.badRequest("ORIGIN_FACT_INVALID", message);
        }
    }

    private String userPrompt(OriginRequest request) {
        return """
                姓名：%s
                性别表达：%s
                种族：%s
                本体根源：%s
                根源描述：%s
                此生所求：%s
                难度：%s
                """.formatted(request.name().strip(), request.genderExpression().strip(),
                raceName(Race.parse(request.race())), request.rootType().strip(),
                request.rootDescription().strip(), request.lifeGoal().strip(), request.difficulty());
    }

    private String fingerprint(OriginRequest request) {
        String source = String.join("\u001f", request.name(), request.genderExpression(), request.race(),
                request.rootType(), request.rootDescription(), request.lifeGoal(), request.difficulty());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String raceName(Race race) {
        return switch (race) {
            case HUMAN -> "人族";
            case DEMON -> "妖族";
            case SPIRIT -> "灵族";
        };
    }

    private FateCandidate fallback(OriginRequest request) {
        Race race = Race.parse(request.race());
        String identity = switch (race) {
            case HUMAN -> "初入青岐州的" + request.rootType().strip();
            case DEMON -> "行于青岐边地的" + request.rootType().strip();
            case SPIRIT -> "刚刚萌生连续自我的" + request.rootType().strip();
        };
        return new FateCandidate("legacy-origin", "未定之命", identity,
                "此身刚刚进入青岐州，前路尚未留下定论。", "尚无已经确认的旧债");
    }

    public record OriginRequest(String name, String genderExpression, String race, String rootType,
                                String rootDescription, String lifeGoal, String difficulty) {
    }

    public record FateCandidate(String id, String title, String publicIdentity,
                                String openingSituation, String unpaidDebt) {
    }

    public record OriginProposal(String proposalId, Instant expiresAt, List<FateCandidate> candidates) {
    }
}
