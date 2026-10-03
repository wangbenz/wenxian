package com.wenxian.game.story.worldpackage.application;

import java.util.List;

/** 世界包未通过结构或语义校验；包含可直接映射到编辑器字段的稳定问题列表。 */
public final class WorldPackageValidationException extends IllegalArgumentException {
    private final List<Violation> violations;

    public WorldPackageValidationException(List<Violation> violations) {
        super(message(violations));
        this.violations = List.copyOf(violations);
    }

    public List<Violation> violations() {
        return violations;
    }

    public record Violation(String code, String path, String message) {}

    private static String message(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) return "世界包校验失败";
        Violation first = violations.getFirst();
        return "世界包校验失败：" + first.path() + " " + first.message()
                + (violations.size() == 1 ? "" : "（另有 " + (violations.size() - 1) + " 项）");
    }
}
