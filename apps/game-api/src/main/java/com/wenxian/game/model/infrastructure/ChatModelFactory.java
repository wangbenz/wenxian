package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import dev.langchain4j.model.chat.ChatModel;

/** 根据中立模型定义创建供应商客户端；注册表负责复用实例。 */
public interface ChatModelFactory {
    ChatModel create(ModelDefinition definition, ModelCredential credential, ModelInvocationOptions options);
}
