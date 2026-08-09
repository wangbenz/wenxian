package com.wenxian.game.story.agent;

/**
 * 为故事类模型调用解析服务端可用路由。
 *
 * <p>实现可以读取平台配置或玩家自备密钥，但返回对象只在后端内存中使用，不能写入 Prompt、日志或玩家响应。</p>
 */
public interface StoryModelRouteResolver {
    StoryModelRoute resolve(String userId);
}
