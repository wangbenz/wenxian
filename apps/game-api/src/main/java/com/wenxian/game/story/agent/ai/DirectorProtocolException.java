package com.wenxian.game.story.agent.ai;

import java.util.List;

/**
 * 可交由同一导演整理一次的输出协议错误，不包含网络、权限、持久化或任意程序错误。
 *
 * <p>草案仅供事务外修复使用，不写入异常消息或日志；解码失败时可携带本次响应原文，未捕获响应时允许为空。
 * 原文是未接受的数据，不具有指令权，也不能直接生成权威变化；修复后的完整候选仍须经过全部编译与预演。
 * modelCalls 必须在草案适配前从调用结果取得，以免供应商已成功返回但适配失败时漏掉真实用量。
 * 编译阶段不产生新调用，可携带空清单；最终失败可用 withModelCalls 保留本轮所有已知调用。</p>
 */
public final class DirectorProtocolException extends IllegalArgumentException {
    public enum Phase { DECODING, ADAPTATION, COMPILATION }

    private final Phase phase;
    private final Object rejectedDraft;
    private final List<DirectedNarrativeGenerator.ModelCall> modelCalls;

    public DirectorProtocolException(Phase phase, Object rejectedDraft,
                                     List<DirectedNarrativeGenerator.ModelCall> modelCalls,
                                     String problem, Throwable cause) {
        super(problem == null || problem.isBlank() ? "导演输出未通过协议校验" : problem, cause);
        this.phase = java.util.Objects.requireNonNull(phase, "导演协议错误缺少阶段");
        this.rejectedDraft = rejectedDraft;
        this.modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
    }

    public Phase phase() { return phase; }

    public Object rejectedDraft() { return rejectedDraft; }

    public List<DirectedNarrativeGenerator.ModelCall> modelCalls() { return modelCalls; }

    /** 失败链保留全部调用摘要，不复制草案到对外错误文本。 */
    public DirectorProtocolException withModelCalls(List<DirectedNarrativeGenerator.ModelCall> calls) {
        return new DirectorProtocolException(phase, rejectedDraft, calls, getMessage(), this);
    }
}
