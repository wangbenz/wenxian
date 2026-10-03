package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ServicePlanSelection;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.InvocationContext;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.domain.StoryRecords;

import java.util.List;
import java.util.Optional;

/**
 * 正式完整故事候选的模型端口；会话先冻结单一任务的方案、路由和凭据来源，再由用例预占额度。
 *
 * <p>一次生成同时返回正文、状态命令和持续记录。这里只取得未接受候选，不做领域归并或写库；
 * 格式与领域错误共用的唯一修复由上层掌握，端口不会自行重试或另调正文模型。</p>
 */
public interface StoryCandidateGenerator {
    Session open(InvocationContext invocation);

    interface Session {
        CredentialSource credentialSource();

        default Optional<ServicePlanSelection> servicePlanSelection() { return Optional.empty(); }

        Generated generate(StoryPromptContext context, List<StoryRecords.Envelope> history,
                           DirectorProtocolException rejected, Object serverAlignment);
    }

    /** 原稿只交付事务外诊断与修复，不能写入玩家 API 或用作状态正确性的自证。 */
    record Generated(StoryCandidateProtocol.Draft draft, StoryCandidateProtocol.Adapted adapted,
                     List<ModelCall> calls, String rawResponse, List<StoryCandidateProtocol.Downgrade> downgrades) {
        public Generated {
            calls = calls == null ? List.of() : List.copyOf(calls);
            downgrades = downgrades == null ? List.of() : List.copyOf(downgrades);
        }

        /** 固定响应的离线测试无需伪造供应商原文。 */
        public Generated(StoryCandidateProtocol.Draft draft, StoryCandidateProtocol.Adapted adapted, List<ModelCall> calls) {
            this(draft, adapted, calls, null, List.of());
        }
    }
}
