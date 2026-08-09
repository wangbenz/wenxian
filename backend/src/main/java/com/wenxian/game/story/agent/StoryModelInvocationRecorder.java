package com.wenxian.game.story.agent;

/**
 * 单次模型请求的中性观察端口。
 *
 * <p>模型请求、成功业务回合和最终玩家错误是三种不同事实。实现层可以记录每次首次生成、格式修复和失败请求，
 * 但不得把统计写入失败升级为玩家回合失败，也不得保存密钥、完整 Prompt 或供应商响应正文。</p>
 */
public interface StoryModelInvocationRecorder {
    StoryModelInvocationRecorder NOOP = new StoryModelInvocationRecorder() {};

    default void succeeded(StoryTurnAgent.InvocationContext invocation, String operation,
                           StoryModelRoute route, StoryModelGateway.Result result) {}

    default void failed(StoryTurnAgent.InvocationContext invocation, String operation,
                        StoryModelRoute route, long latencyMs, RuntimeException failure) {}
}
