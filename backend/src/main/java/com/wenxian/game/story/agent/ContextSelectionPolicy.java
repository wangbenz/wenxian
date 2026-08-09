package com.wenxian.game.story.agent;

import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将结构化相关性信号合并为 Prompt 工作集。
 *
 * <p>当前场景、近期已接受补丁、玩家明确引用和语义检索都只能提供稳定引用；本策略不读取玩家文本，
 * 不使用姓名词表，也不按固定 Token 预算裁剪事实。任何过期或错误引用由
 * {@link PromptContextAssembler} 在调用模型前拒绝。</p>
 */
public final class ContextSelectionPolicy {

    public ContextSelection select(SelectionSignals signals) {
        if (signals == null) return ContextSelection.empty();

        Set<String> entityIds = union(signals.sceneEntityIds(), signals.recentEntityIds(),
                signals.explicitEntityIds(), signals.semanticEntityIds());
        Set<String> worldFactIds = union(signals.sceneWorldFactIds(), signals.explicitWorldFactIds(),
                signals.semanticWorldFactIds());
        Set<String> memoryIds = union(signals.recentMemoryIds(), signals.explicitMemoryIds(),
                signals.semanticMemoryIds());
        List<KnowledgeRef> knowledge = mergeKnowledge(signals.sceneKnowledgeRefs(),
                signals.explicitKnowledgeRefs(), signals.semanticKnowledgeRefs());

        return new ContextSelection(entityIds, entityIds, worldFactIds, knowledge, memoryIds);
    }

    @SafeVarargs
    private final Set<String> union(Set<String>... sources) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> source : sources) {
            if (source == null) continue;
            source.stream().filter(this::present).map(String::strip).forEach(result::add);
        }
        return Set.copyOf(result);
    }

    @SafeVarargs
    private final List<KnowledgeRef> mergeKnowledge(List<KnowledgeRef>... sources) {
        Set<KnowledgeRef> result = new LinkedHashSet<>();
        for (List<KnowledgeRef> source : sources) {
            if (source == null) continue;
            source.stream().filter(value -> value != null && present(value.subjectRef())
                    && present(value.topicRef()))
                    .map(value -> new KnowledgeRef(value.subjectRef().strip(), value.topicRef().strip()))
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record SelectionSignals(
            Set<String> sceneEntityIds,
            Set<String> recentEntityIds,
            Set<String> explicitEntityIds,
            Set<String> semanticEntityIds,
            Set<String> sceneWorldFactIds,
            Set<String> explicitWorldFactIds,
            Set<String> semanticWorldFactIds,
            List<KnowledgeRef> sceneKnowledgeRefs,
            List<KnowledgeRef> explicitKnowledgeRefs,
            List<KnowledgeRef> semanticKnowledgeRefs,
            Set<String> recentMemoryIds,
            Set<String> explicitMemoryIds,
            Set<String> semanticMemoryIds
    ) {}
}
