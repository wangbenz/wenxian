package com.wenxian.demo.dm;

import java.nio.file.*;
import java.util.*;

/** 仅读取选定模型配置与演示环境变量；密钥不返回浏览器、不复制到存档、不生成toString。 */
final class DemoConfig {
    final Path dataDir = Path.of(env("DEMO_DATA_DIR", ".data")).toAbsolutePath().normalize();
    final String loreUrl = env("LOREFORGE_API_URL", "http://127.0.0.1:18087");
    final String host = env("DEMO_HOST", "127.0.0.1");
    final int port = Integer.parseInt(env("DEMO_PORT", "5189"));
    final boolean enabled = Boolean.parseBoolean(env("DEMO_MODEL_ENABLED", "false"));
    final int maxCalls = Integer.parseInt(env("DEMO_MAX_MODEL_REQUESTS", "20"));
    final String baseUrl;
    final String model;
    final String apiKey;
    DemoConfig() {
        Map<String,String> local = new HashMap<>();
        Path path = Path.of(env("DEMO_MODEL_ENV_FILE", "../../apps/game-api/.env.local"));
        if (Files.isRegularFile(path)) try {
            for (String line : Files.readAllLines(path)) {
                String value = line.trim();
                if (value.startsWith("#") || !value.contains("=")) continue;
                String[] pair = value.replaceFirst("^export\\s+", "").split("=", 2);
                if (!Set.of("DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL", "DEEPSEEK_MODEL").contains(pair[0].trim())) continue;
                String v = pair[1].trim();
                if (v.length()>1 && ((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'")))) v=v.substring(1,v.length()-1);
                local.put(pair[0].trim(),v);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("模型配置文件无法读取"); }
        apiKey = env("DEEPSEEK_API_KEY", local.getOrDefault("DEEPSEEK_API_KEY", ""));
        baseUrl = env("DEEPSEEK_BASE_URL", local.getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"));
        model = env("DEEPSEEK_MODEL", local.getOrDefault("DEEPSEEK_MODEL", "deepseek-v4-flash"));
        if (maxCalls < 0 || maxCalls > 200) throw new IllegalArgumentException("调用上限应为0至200");
    }
    boolean ready() { return enabled && !apiKey.isBlank(); }
    static String env(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
}
