package com.wenxian.game.story.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验匿名真实问题回放夹具的结构和隐私边界。
 *
 * <p>本测试暂不调用剧情模型，也不假装验证了业务修复。它只冻结后续 StoryPatch、Reducer、Prompt
 * 和 H5 必须共同通过的六类验收输入，防止实现阶段再次按单个线上问题零散增加规则。</p>
 */
class TrustedStoryReplayFixtureTest {
    private static final String FIXTURE = "/story/trusted-story-replay-cases.json";
    private static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "CHARACTER_LIFECYCLE",
            "ABILITY_AUTHORITY",
            "ITEM_OWNERSHIP",
            "WORLD_TIME",
            "PLAYER_AGENCY",
            "WORLD_AMENDMENT"
    );

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void containsSixAnonymousAndCompletePlayerProblemReplays() throws Exception {
        InputStream stream = getClass().getResourceAsStream(FIXTURE);
        assertNotNull(stream, "必须提交可信叙事回放夹具");
        JsonNode root;
        try (stream) {
            root = mapper.readTree(stream);
        }

        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("ANONYMIZED_PLAYER_REPORTS", root.path("source").asText());
        JsonNode cases = root.path("cases");
        assertTrue(cases.isArray());
        assertEquals(6, cases.size());

        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        for (JsonNode replay : cases) {
            assertTrue(ids.add(requiredText(replay, "id")), "回放ID必须唯一");
            categories.add(requiredText(replay, "category"));
            requiredText(replay, "action");
            assertTrue(replay.path("given").isObject(), "每个回放必须提供前置权威事实");
            assertTrue(replay.path("expected").isObject(), "每个回放必须提供预期结果");
            assertTrue(replay.path("forbidden").isArray() && !replay.path("forbidden").isEmpty(),
                    "每个回放必须明确禁止的错误行为");
        }
        assertEquals(REQUIRED_CATEGORIES, categories);

        String raw = mapper.writeValueAsString(root);
        assertFalse(raw.matches(".*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*"),
                "回放夹具不得包含玩家邮箱");
        assertFalse(raw.contains("sk-"), "回放夹具不得包含模型密钥");
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        assertFalse(value.isBlank(), field + " 不能为空");
        return value;
    }
}
