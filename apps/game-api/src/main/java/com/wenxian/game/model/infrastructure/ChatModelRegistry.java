package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有界复用线程安全的 ChatModel，避免每个回合重新创建 HTTP 客户端。 */
@Component
public class ChatModelRegistry {
    private final ChatModelFactory factory;
    private final int maximumEntries;
    private final Map<Key, RegisteredChatModel> cache = new LinkedHashMap<>(16, 0.75f, true);

    public ChatModelRegistry(ChatModelFactory factory,
                             @Value("${wenxian.model.chat-registry-maximum-entries:256}") int maximumEntries) {
        if (factory == null) throw new IllegalArgumentException("模型工厂不能为空");
        if (maximumEntries <= 0) throw new IllegalArgumentException("模型缓存上限必须大于零");
        this.factory = factory;
        this.maximumEntries = maximumEntries;
    }

    public synchronized RegisteredChatModel getOrCreate(ModelDefinition definition, ModelCredential credential,
                                                        ModelInvocationOptions options) {
        Key key = new Key(definition, credential.fingerprint(), options);
        RegisteredChatModel existing = cache.get(key);
        if (existing != null) return existing;
        ChatModel model = factory.create(definition, credential, options);
        RegisteredChatModel registered = new RegisteredChatModel(identity(key), definition, model);
        cache.put(key, registered);
        evictEldest();
        return registered;
    }

    /** 玩家删除或轮换密钥时主动清除相应客户端。 */
    public synchronized void invalidateCredential(ModelCredential credential) {
        cache.keySet().removeIf(key -> key.credentialFingerprint().equals(credential.fingerprint()));
    }

    public synchronized int size() {
        return cache.size();
    }

    private void evictEldest() {
        while (cache.size() > maximumEntries) {
            Key eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
    }

    private String identity(Key key) {
        try {
            String raw = key.definition().identity() + "|" + key.definition().baseUrl() + "|"
                    + key.definition().apiModelName() + "|" + key.credentialFingerprint() + "|" + key.options();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private record Key(ModelDefinition definition, String credentialFingerprint, ModelInvocationOptions options) {}

    /** identity 是不含密钥的注册表标识，只用于内部缓存关联。 */
    public record RegisteredChatModel(String identity, ModelDefinition definition, ChatModel model) {}
}
