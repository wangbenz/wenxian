package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.v014.model.V014State;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014PromptAssemblerTest {
    @Test
    void promptContainsAuthoritativeFactsButNotLegacyRpgMetrics() {
        V014State state = new V014State(14, "save", 12, "ACTIVE",
                new V014State.WorldProfile("CUSTOM", "自定命界", "CUSTOM", "矿石可以修炼成灵", "万物成灵", "石海"),
                new V014State.Protagonist("照夜", "灵族", "不设定", "矿石成精", "凝形", "看遍天地", "轻快奇遇"),
                600, "石海", "灵光稳定", "石海风声", "风从裂隙而来。", List.of(),
                List.of(new V014State.Item("item-star", "星铁", "灵材", "仍在行囊", "随身收好", "石海", 1, true),
                        new V014State.Item("item-old", "旧石", "灵材", "曾经所得", "已由玩家移出行囊", "石海", 1, false)),
                List.of(), List.of(new V014State.Character("character-old", "旧识", "KNOWN", "故人", "旧交", "远游", "未知", false)), List.of());
        V014PromptAssembler.Prompt assembled = new V014PromptAssembler(new ObjectMapper())
                .assemble(state, "照夜已经醒来", List.of(), "继续向东");
        String prompt = assembled.user();

        assertTrue(prompt.contains("矿石成精"));
        assertTrue(prompt.contains("星铁"));
        assertTrue(prompt.contains("SECOND_PERSON"));
        assertTrue(assembled.system().contains("始终以第二人称“你”指代主角"));
        assertTrue(assembled.system().contains("人物对白、书信与主角说出的台词可以正常使用“我”"));
        assertFalse(prompt.contains("旧石"));
        assertFalse(prompt.contains("旧识"));
        assertFalse(prompt.contains("vitality"));
        assertFalse(prompt.contains("suspicion"));
    }

    @Test
    void promptKeepsRecentBoundedFactsInsteadOfGrowingForever() {
        List<V014State.Memory> memories = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> new V014State.Memory("m" + index, "记忆" + index,
                        "需要记住的事" + index, index, "KEY"))
                .toList();
        V014State state = new V014State(14, "save", 20, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "古典修仙", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "游侠", "引气", "问道", "自由探索"),
                600, "青岐州", "安然", "山路", "风从山中来。", List.of(),
                List.of(), List.of(), List.of(), memories);

        String prompt = new V014PromptAssembler(new ObjectMapper()).assemble(state, "旧事".repeat(1600), List.of(), "继续向东").user();

        assertFalse(prompt.contains("记忆1\""));
        assertTrue(prompt.contains("记忆20"));
        assertTrue(prompt.length() < 8_000, "长期存档上下文应保持有界");
    }
}
