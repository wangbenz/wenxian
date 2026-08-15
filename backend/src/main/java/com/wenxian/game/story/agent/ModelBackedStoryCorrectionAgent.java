package com.wenxian.game.story.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryCorrectionValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用故事模型解析命卷修正的中性 Agent。
 *
 * <p>该调用只做“预览解释”：不写数据库、不推进时间、不扣回合，也不生成剧情。模型只能在当前
 * 权威实体 ID 和世界事实 ID 上提出变化；格式或层级校验失败时整份提案作废。</p>
 */
@Service
public class ModelBackedStoryCorrectionAgent implements StoryCorrectionAgent {
    private static final String SYSTEM_PROMPT = """
            你只负责把玩家的命卷修正说明解析为结构化提案，不执行修正，不创作剧情，不推进时间。
            objective 是当前客观事实；cognition 是人物知道或相信的内容；memory 和 narrative 只是查找经历的材料，
            不能因为叙事材料出现一句话就静默覆盖 objective。

            只识别玩家明确要求改变的内容，不替玩家扩大修正范围。kind 只允许：
            FACT：地点、处境、境界、人生目标、物品、能力或人物客观生命周期；
            COGNITION：某个主体知道、相信或误解什么，不改变客观世界；
            WORLD：玩家明确扩展或修订当前世界设定。

            如果玩家只是否认 narrative 中尚未执行的打算，而 objective 本来就没有记录该决定，
            不得伪造客观补丁。返回简明 explanation 和 confirmationPrompt，coreState 各字段为 null，
            entities、worldFacts、knowledge 全部为空。服务端会向玩家说明“权威事实未变，无需修正”。

            已有实体只能使用 objective 中提供的稳定 entityId，不能凭名称猜测关联；新实体使用 CREATE 且 entityId 为 null。
            已有世界事实的修订必须使用稳定 factId 和 REVISE；新增世界设定使用 EXPAND 且 factId 为 null。
            FACT 不得夹带 worldFacts 或 knowledge；COGNITION 不得夹带 coreState、entities 或 worldFacts；
            WORLD 不得夹带 coreState、entities 或 knowledge。未修改的字段返回 null，未使用的列表返回空数组。
            explanation 用简短自然语言说明识别到的变化；confirmationPrompt 明确询问玩家是否确认同一变化。

            只返回一个 JSON 对象，不要 Markdown。字段：
            kind,explanation,confirmationPrompt,
            coreState:{location,condition,realm,lifeGoal},
            entities:[{operation,entityId,entityType,name,status,summary,attributes,
              quantity:{known,value},inPossession,abilityState,lifecycle:{state,changeReason},
              nameChange:{explicit,reason}}],
            worldFacts:[{operation,factId,statement,reason}],
            knowledge:[{subjectRef,topicRef,summary,evidenceRefs}]。
            operation 实体只允许 CREATE、UPDATE、HIDE、RESTORE；abilityState 只允许
            KNOWN、LEARNING、MASTERED、TEMPORARILY_BLOCKED、LOST、UNKNOWN；人物生命周期 state 只允许
            ACTIVE、MISSING、DORMANT、DEAD、UNKNOWN；世界 operation 只允许 EXPAND、REVISE。
            修改已有实体名称时必须是玩家明确要求的改名，并填写 nameChange.explicit=true 与具体依据；
            普通称谓变化不得改变稳定实体名称。
            """;

    private final StoryModelGateway gateway;
    private final ObjectMapper mapper;
    private final ObjectMapper tolerantMapper;
    private final StoryCorrectionValidator validator;

    @Autowired
    public ModelBackedStoryCorrectionAgent(StoryModelGateway gateway, ObjectMapper mapper) {
        this(gateway, mapper, new StoryCorrectionValidator());
    }

    ModelBackedStoryCorrectionAgent(StoryModelGateway gateway, ObjectMapper mapper,
                                    StoryCorrectionValidator validator) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.tolerantMapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.validator = validator;
    }

    @Override
    public Generated propose(StoryPromptContext context, String instruction, StoryModelRoute route) {
        if (context == null) throw new IllegalArgumentException("修正预览缺少命卷上下文");
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("修正说明不能为空");
        if (route == null) throw new IllegalArgumentException("修正预览缺少模型路由");

        StoryModelGateway.Result result = gateway.generate(new StoryModelGateway.Request(
                route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(),
                SYSTEM_PROMPT, userPrompt(context, instruction.strip()), 0.1));
        try {
            StoryCorrectionProposal proposal = tolerantMapper.readValue(rawJson(result.text()),
                    StoryCorrectionProposal.class);
            if (!hasObjectiveChange(proposal)) {
                throw new StoryCorrectionNoChangeException(
                        "这段说明没有改变当前权威事实；未经玩家确认的叙事打算不会作为已执行行动继承。");
            }
            validator.validate(proposal);
            return new Generated(proposal, result);
        } catch (StoryCorrectionNoChangeException error) {
            throw error;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("修正说明未能形成可确认提案", error);
        }
    }

    private boolean hasObjectiveChange(StoryCorrectionProposal proposal) {
        if (proposal == null) return false;
        var core = proposal.coreState();
        boolean coreChanged = core != null && (present(core.location()) || present(core.condition())
                || present(core.realm()) || present(core.lifeGoal()));
        return coreChanged || !proposal.entities().isEmpty() || !proposal.worldFacts().isEmpty()
                || !proposal.knowledge().isEmpty();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private String userPrompt(StoryPromptContext context, String instruction) {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("objective", context.objective());
        sections.put("cognition", context.cognition());
        sections.put("memory", context.memory());
        sections.put("narrative", context.narrative());
        sections.put("playerCorrectionInstruction", instruction);
        try {
            return mapper.writeValueAsString(sections);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("修正预览上下文无法序列化", error);
        }
    }

    private String rawJson(String raw) {
        if (raw == null) return "{}";
        String value = raw.strip();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).strip();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }
}
