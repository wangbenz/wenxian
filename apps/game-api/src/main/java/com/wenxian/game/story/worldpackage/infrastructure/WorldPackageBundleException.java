package com.wenxian.game.story.worldpackage.infrastructure;

/** Bundle 在安全读取、文件校验或严格 JSON 解析阶段失败；此时不得继续编译或持久化。 */
public final class WorldPackageBundleException extends IllegalArgumentException {
    private final String code;
    private final String path;

    public WorldPackageBundleException(String code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public WorldPackageBundleException(String code, String path, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.path = path;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }
}
