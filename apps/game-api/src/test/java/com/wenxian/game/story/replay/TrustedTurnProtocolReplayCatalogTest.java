package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验精简可信回合的新旧链路共用回放目录。
 *
 * <p>目录只保存匿名化的最小前置事实、玩家行动和不可破坏的验收不变量，不保存玩家邮箱、命卷号、
 * 完整历史正文或模型密钥。后续旧链路基线与候选链路必须读取同一目录，不能为某条实现单独改写样本。</p>
 */
class TrustedTurnProtocolReplayCatalogTest {
    private static final String FIXTURE = "/story/trusted-turn-protocol-replay-cases.json";
    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "DIALOGUE", "EXPLORATION", "TRAINING", "COMBAT", "ITEM", "CHARACTER",
            "TIME", "AGENCY", "FACT_CLAIM", "WORLD_AMENDMENT", "SYSTEM_BOUNDARY"
    );
    private static final Set<String> EXPECTED_FLOWS = Set.of(
            "NORMAL_STORY", "PRE_MODEL_REFRESH", "CORRECTION_FLOW"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PROBLEM_REFERENCE = Pattern.compile("WX-[0-9A-F-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAVE_ID = Pattern.compile("story-[0-9a-f]{8}-[0-9a-f-]{27,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern MODEL_KEY = Pattern.compile("(?<![A-Za-z0-9])sk-[A-Za-z0-9]{16,}",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void catalogIsAnonymousCompleteAndSharedByOldAndCandidatePipelines() throws Exception {
        JsonNode root = readFixture();

        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("ANONYMIZED_GRAY_AND_PLAYER_BEHAVIOR", requiredText(root, "source"));
        assertFalse(requiredText(root, "purpose").isBlank());
        JsonNode cases = root.path("cases");
        assertTrue(cases.isArray());
        assertTrue(cases.size() >= 18, "对照目录至少覆盖十八种代表性行为，不能只保留六个落库样本");

        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        Set<String> flows = new HashSet<>();
        for (JsonNode replay : cases) {
            String id = requiredText(replay, "id");
            String category = requiredText(replay, "category");
            String flow = requiredText(replay, "expectedFlow");
            assertTrue(ids.add(id), "回放ID必须唯一：" + id);
            assertTrue(REQUIRED_CATEGORIES.contains(category), "出现未声明的回放分类：" + category);
            assertTrue(EXPECTED_FLOWS.contains(flow), "出现未声明的期望流程：" + flow);
            assertTrue(Set.of("FREE_TEXT", "SERVER_CHOICE").contains(requiredText(replay, "actionSource")));
            requiredText(replay, "action");
            assertTrue(replay.path("authority").isArray() && !replay.path("authority").isEmpty(),
                    id + " 必须提供最小权威或场景前提");
            assertTrue(replay.path("invariants").isArray() && !replay.path("invariants").isEmpty(),
                    id + " 必须提供可复用验收不变量");
            assertTrue(replay.path("forbidden").isArray() && !replay.path("forbidden").isEmpty(),
                    id + " 必须明确禁止的历史缺陷");
            int maxModelCalls = replay.path("maxModelCalls").asInt(-1);
            assertTrue(maxModelCalls >= 0 && maxModelCalls <= 3,
                    id + " 的模型调用预算必须处于0至3次之间");
            if ("PRE_MODEL_REFRESH".equals(flow)) {
                assertEquals(0, maxModelCalls, "模型前系统拒绝不得产生模型调用");
            }
            categories.add(category);
            categoryCounts.merge(category, 1, Integer::sum);
            flows.add(flow);
        }

        assertEquals(REQUIRED_CATEGORIES, categories);
        assertEquals(EXPECTED_FLOWS, flows);
        assertTrue(categoryCounts.getOrDefault("ITEM", 0) >= 3, "物品必须覆盖消耗、赠送和错误前提");
        assertTrue(categoryCounts.getOrDefault("AGENCY", 0) >= 2, "决定权必须覆盖重大决定和自然收尾");
        assertTrue(categoryCounts.getOrDefault("TIME", 0) >= 2, "时间必须覆盖短等待和长期闭关");

        String raw = mapper.writeValueAsString(root);
        assertNoPrivateIdentifier(raw, EMAIL, "玩家邮箱");
        assertNoPrivateIdentifier(raw, PROBLEM_REFERENCE, "线上问题编号");
        assertNoPrivateIdentifier(raw, SAVE_ID, "真实命卷编号");
        assertNoPrivateIdentifier(raw, PHONE, "手机号或QQ群号");
        assertNoPrivateIdentifier(raw, MODEL_KEY, "模型密钥");
    }

    @Test
    void catalogKeepsPlayerCopyFreeOfInternalProtocolVocabulary() throws Exception {
        JsonNode cases = readFixture().path("cases");
        Set<String> internalTerms = Set.of(
                "ACTIVE", "ESTABLISHED", "PROMOTE_ON_CHANGE", "referenceKey", "statePatch",
                "deepseek", "REPAIR_PROPOSAL", "SYSTEM_FAILURE"
        );
        for (JsonNode replay : cases) {
            String action = requiredText(replay, "action");
            for (String term : internalTerms) {
                assertFalse(action.contains(term), replay.path("id").asText() + " 的玩家行动泄漏内部词：" + term);
            }
        }
    }

    private JsonNode readFixture() throws Exception {
        InputStream stream = getClass().getResourceAsStream(FIXTURE);
        assertNotNull(stream, "必须提交精简可信回合对照目录");
        try (stream) {
            return mapper.readTree(stream);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        assertFalse(value.isBlank(), field + " 不能为空");
        return value;
    }

    private void assertNoPrivateIdentifier(String raw, Pattern pattern, String label) {
        assertFalse(pattern.matcher(raw).find(), "回放目录不得包含" + label);
    }
}
