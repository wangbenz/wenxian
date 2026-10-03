package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ServicePlanSelection;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.StoryPatch;

import java.util.List;
import java.util.Optional;

/**
 * “轻量导演 + 正文演绎”的类型化模型端口。
 *
 * <p>导演只返回语义事实意图，正文只描述已经由服务端编译并预演通过的计划。实现不得写数据库、扣回合，
 * 也不得把正文重新解释成事实。</p>
 */
public interface DirectedNarrativeGenerator {
    /** 在本轮第一次供应商调用前解析并冻结导演、正文两项任务路由。 */
    Session open(InvocationContext invocation);

    interface Session {
        /** 本轮冻结路由使用的平台或玩家密钥来源；额度判断只能读取该脱敏结果。 */
        CredentialSource credentialSource();

        /** 平台模式返回请求开始时冻结的版本、档位和价格；个人密钥与兼容旧路由为空。 */
        default Optional<ServicePlanSelection> servicePlanSelection() {
            return Optional.empty();
        }

        DirectedPlan plan(StoryPromptContext context);

        DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem);

        /**
         * 修复尚未转成领域意图的协议草案；与领域编译修复共用本轮唯一一次预算。
         *
         * <p>真实模型适配器应覆写并携带异常中的原始草案与阶段。默认桥接仅用于兼容原有固定 Session，
         * 不能假设解码失败一定留有可读取的响应正文，也不得重新读取命卷或重新预占额度。</p>
         */
        default DirectedPlan repairProtocol(StoryPromptContext context, DirectorProtocolException rejected) {
            return repair(context, null, rejected.getMessage());
        }

        RenderedNarrative render(StoryPromptContext context,
                                 StoryPatch acceptedPlan, NarrativeEndState endState);
    }

    /** 一次完整回合在模型路由和观测中的稳定位置，不携带 Prompt 或玩家隐私。 */
    record InvocationContext(String userId, String requestId, String saveId, int turnNumber) {
        public InvocationContext {
            if (userId == null || userId.isBlank()) throw new IllegalArgumentException("模型调用缺少账号ID");
            if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("模型调用缺少请求号");
            if (saveId == null || saveId.isBlank()) throw new IllegalArgumentException("模型调用缺少命卷ID");
            if (turnNumber < 1) throw new IllegalArgumentException("模型调用回合号必须大于0");
        }
    }

    /** 不含密钥、Prompt 和响应正文的单次供应商调用摘要。 */
    record ModelCall(String providerCode, String modelCode, String credentialSource,
                     boolean fallback, String status, long latencyMs,
                     int inputTokens, int outputTokens, int cacheHitTokens, int cacheMissTokens,
                     String errorCategory) {}

    /** 轻量导演产生的语义事实意图及全部真实模型尝试。 */
    record DirectedPlan(StoryTurnDecision decision, List<ModelCall> modelCalls) {
        public DirectedPlan {
            if (decision == null) throw new IllegalArgumentException("轻量导演结果不能为空");
            modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
            if (modelCalls.isEmpty()) throw new IllegalArgumentException("轻量导演必须保留模型调用记录");
        }
    }

    /** 只含玩家可见表现层的正文结果，不能重新取得事实写权限。 */
    record RenderedNarrative(String title, String narrative, List<StoryPatch.ChoiceDraft> choices,
                             String rollingSummary, List<ModelCall> modelCalls) {
        public RenderedNarrative {
            title = title == null ? "" : title.strip();
            narrative = narrative == null ? "" : narrative.strip();
            choices = choices == null ? List.of() : List.copyOf(choices);
            rollingSummary = rollingSummary == null ? "" : rollingSummary.strip();
            modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
            if (title.isBlank()) throw new IllegalArgumentException("正文缺少标题");
            if (narrative.isBlank()) throw new IllegalArgumentException("正文不能为空");
            if (modelCalls.isEmpty()) throw new IllegalArgumentException("正文必须保留模型调用记录");
        }
    }
}
