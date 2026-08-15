package com.wenxian.game.story.application;

import com.wenxian.game.story.agent.StoryCorrectionAgent;
import com.wenxian.game.story.agent.StoryModelGateway;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.reducer.StoryCorrectionReducer;

/**
 * 两阶段命卷修正的应用层工作流。
 *
 * <p>预览阶段调用一次修正 Agent，校验后将完整提案封装为绑定账号、命卷和 revision 的认证令牌；
 * 确认阶段只验证该令牌并执行 Reducer，绝不重新调用模型。正式接入数据库时，调用方必须把投影写入、
 * revision 递增和修正审计放在同一事务。</p>
 */
public final class StoryCorrectionWorkflow {
    private final StoryCorrectionAgent agent;
    private final CorrectionProposalTokenCodec tokens;
    private final StoryCorrectionReducer reducer;

    public StoryCorrectionWorkflow(StoryCorrectionAgent agent, CorrectionProposalTokenCodec tokens) {
        this(agent, tokens, new StoryCorrectionReducer());
    }

    StoryCorrectionWorkflow(StoryCorrectionAgent agent, CorrectionProposalTokenCodec tokens,
                            StoryCorrectionReducer reducer) {
        if (agent == null) throw new IllegalArgumentException("修正工作流缺少解析Agent");
        if (tokens == null) throw new IllegalArgumentException("修正工作流缺少提案令牌组件");
        if (reducer == null) throw new IllegalArgumentException("修正工作流缺少事实归并器");
        this.agent = agent;
        this.tokens = tokens;
        this.reducer = reducer;
    }

    /** 生成玩家可读预览；不会改变命卷，也不消耗正式回合。 */
    public Preview preview(String userId, String saveId, long expectedRevision, String instruction,
                           StoryPromptContext context, StoryModelRoute route) {
        StoryCorrectionAgent.Generated generated = agent.propose(context, instruction, route);
        StoryCorrectionProposal proposal = generated.proposal();
        String proposalToken = tokens.issue(userId, saveId, expectedRevision, instruction, proposal);
        return new Preview(proposal.kind(), proposal.explanation(), proposal.confirmationPrompt(),
                proposalToken, generated.modelResult());
    }

    /** 验证并应用预览时的同一份提案；该方法没有模型调用入口。 */
    public Confirmed confirm(String userId, String saveId, long expectedRevision, String proposalToken,
                             StoryProjection currentProjection) {
        if (currentProjection == null) throw new IllegalArgumentException("修正确认缺少当前命卷投影");
        CorrectionProposalTokenCodec.VerifiedProposal verified = tokens.verify(
                proposalToken, userId, saveId, expectedRevision);
        StoryCorrectionReducer.Applied applied = reducer.apply(currentProjection, verified.proposal(), true);
        return new Confirmed(verified.instruction(), applied.projection(), applied.acceptedProposal(),
                applied.aliasChanges());
    }

    public record Preview(
            StoryCorrectionProposal.CorrectionKind kind,
            String explanation,
            String confirmationPrompt,
            String proposalToken,
            StoryModelGateway.Result modelResult
    ) {}

    public record Confirmed(
            String instruction,
            StoryProjection projection,
            StoryCorrectionProposal acceptedProposal,
            java.util.List<com.wenxian.game.story.reducer.StoryStateReducer.EntityAliasChange> aliasChanges
    ) {}
}
