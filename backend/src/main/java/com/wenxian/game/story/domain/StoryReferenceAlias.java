package com.wenxian.game.story.domain;

/**
 * 表示单个候选回合内部、尚未获得正式实体 ID 的临时引用。
 *
 * <p>该引用只允许在行动契约与候选补丁之间流转。Reducer 会在同一事务提交前为对应新实体分配
 * 服务端 ID，并把所有保管关系替换为正式 ID；临时引用不得进入当前事实投影。</p>
 */
public final class StoryReferenceAlias {
    private static final String PREFIX = "contract-ref:";

    private StoryReferenceAlias() {}

    public static String holderRef(String referenceKey) {
        String key = normalizeKey(referenceKey);
        if (key == null) throw new IllegalArgumentException("回合内引用键不能为空");
        return PREFIX + key;
    }

    public static boolean isAlias(String holderRef) {
        return holderRef != null && holderRef.strip().startsWith(PREFIX);
    }

    public static String keyOf(String holderRef) {
        if (!isAlias(holderRef)) return null;
        return normalizeKey(holderRef.strip().substring(PREFIX.length()));
    }

    public static String normalizeKey(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.strip();
        if (!key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("回合内引用键格式无效");
        }
        return key;
    }
}
