package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实模型验证统一个人密钥入口，不访问 RDS，也不写玩家数据。 */
class LangChain4jProviderCredentialVerifierRealModelTest {

    @Test
    void verifiesCredentialThroughSharedLangChain4jFactory() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run credential smoke");
        ModelDefinition model = new ModelDefinition("DEEPSEEK", "TEST_MODEL",
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"),
                ModelAdapterType.OPENAI_COMPATIBLE,
                value(System.getenv("DEEPSEEK_MODEL"), "deepseek-v4-flash"),
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                128_000, 8_000);
        long started = System.nanoTime();

        new LangChain4jProviderCredentialVerifier(new OpenAiCompatibleChatModelFactory()).verify(model, key);

        System.out.printf("CREDENTIAL_VERIFY_LANGCHAIN4J latencyMs=%d model=%s%n",
                (System.nanoTime() - started) / 1_000_000, model.identity());
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
