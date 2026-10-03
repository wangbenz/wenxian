package com.wenxian.game.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 账号外观、公告和推演额度等平台级接口模型。
 *
 * <p>这些契约不属于任何故事引擎，也不能引用命卷状态或产品版本包。剧情创建与推进统一使用
 * {@code story.model.StoryApiModels}。</p>
 */
public final class PlatformApiModels {
    private PlatformApiModels() {}

    public record GenerateNameRequest(
            @NotBlank @Pattern(regexp = "MALE|FEMALE|UNSPECIFIED") String gender,
            @NotBlank @Pattern(regexp = "HUMAN|DEMON|SPIRIT") String race,
            @Size(max = 20) List<@Size(max = 12) String> exclude
    ) {}

    public record GenerateNameResponse(String name, List<String> alternatives) {}

    public record ValidateNameRequest(@NotBlank @Size(max = 12) String name) {}

    public record ValidateNameResponse(boolean valid, String normalizedName, String reasonCode, String message) {}

    public record ReleaseNoteView(String id, String version, String title, String content,
                                  Instant publishedAt, boolean unread) {}

}
