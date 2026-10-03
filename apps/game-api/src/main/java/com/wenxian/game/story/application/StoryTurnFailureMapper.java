package com.wenxian.game.story.application;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.story.agent.StoryModelFailureException;
import com.wenxian.game.story.agent.StoryModelSelectionGateway;
import com.wenxian.game.story.agent.StoryModelSelectionGateway.StoryModelSelection;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import dev.langchain4j.exception.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 将回合准备阶段的内部异常翻译成稳定、可恢复且不泄漏供应商细节的玩家错误。
 *
 * <p>此处只翻译失败，不消费额度、不写命卷。个人密钥被供应商拒绝时会标记为失效，避免玩家反复提交同一坏密钥。</p>
 */
@Service
public class StoryTurnFailureMapper {
    private final StoryModelSelectionGateway modelSelections;

    public StoryTurnFailureMapper(StoryModelSelectionGateway modelSelections) {
        this.modelSelections = modelSelections;
    }

    /** 未识别的运行时异常原样返回，交给统一异常处理器记录问题编号。 */
    public RuntimeException map(String userId, CredentialSource credentialSource, RuntimeException error) {
        boolean personal = credentialSource == CredentialSource.USER;
        if (personal && causedByAuthentication(error)) {
            StoryModelSelection selection = modelSelections.currentSelection(userId);
            modelSelections.markCredentialInvalid(userId, selection.provider(), "AUTHENTICATION_FAILED");
            return new GameApiException(HttpStatus.CONFLICT, "PERSONAL_KEY_REJECTED", "CREDENTIAL",
                    "自己的推演密钥已失效", "推演服务拒绝了当前密钥，本次未消耗回合或修改命卷。",
                    List.of("前往设置重新验证密钥", "确认密钥仍有额度且未被停用"), false,
                    false, false, Map.of(), "personal credential rejected by provider", error);
        }

        StoryModelFailureException modelFailure = findModelFailure(error);
        if (modelFailure != null) return playerSafeModelFailure(modelFailure, personal);

        if (error instanceof StoryActionPreconditionException conflict) {
            List<String> suggestions = conflict.correction().isBlank()
                    ? List.of("刷新命卷后重新描述行动") : List.of(conflict.correction());
            return new GameApiException(HttpStatus.CONFLICT, "ACTION_PRECONDITION_FAILED", "STATE",
                    "这次行动的前提与命卷不一致", conflict.getMessage(), suggestions,
                    false, false, false, Map.of("correction", conflict.correction()),
                    "story action fact contract rejected", conflict);
        }
        if (error instanceof StoryTurnProtocolException protocol) {
            return new GameApiException(HttpStatus.BAD_GATEWAY, "STORY_INTERNAL_RECOVERY_FAILED", "SYSTEM",
                    "命卷未能整理本轮变化",
                    "系统没有把本轮剧情与事实变化整理成可安全保存的结果；本次未消耗回合，命卷也没有变化。",
                    List.of("重新尝试本轮行动", "若重复出现，请将问题编号发给管理员"),
                    true, false, false, Map.of("stage", protocol.stage()),
                    "trusted story protocol recovery failed at " + protocol.stage(), protocol);
        }
        if (error instanceof IllegalArgumentException) {
            return GameApiException.temporary("STORY_PATCH_REJECTED", "这一轮没有形成一致结果",
                    "系统已阻止不完整或相互矛盾的变化写入命卷，本次未消耗回合。",
                    List.of("重新尝试本轮行动", "换一种更明确的方式描述行动"),
                    "trusted story turn proposal rejected", error);
        }
        return error;
    }

    private boolean causedByAuthentication(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 8) {
            if (cursor instanceof AuthenticationException) return true;
            if (cursor instanceof StoryModelFailureException failure
                    && failure.kind() == StoryModelFailureException.Kind.AUTHENTICATION) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private StoryModelFailureException findModelFailure(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 8) {
            if (cursor instanceof StoryModelFailureException failure) return failure;
            cursor = cursor.getCause();
        }
        return null;
    }

    private GameApiException playerSafeModelFailure(StoryModelFailureException failure, boolean personal) {
        return switch (failure.kind()) {
            case REQUEST_REJECTED -> personal
                    ? new GameApiException(HttpStatus.CONFLICT, "PERSONAL_GENERATION_REJECTED", "CREDENTIAL",
                    "自己的推演服务没有接受请求",
                    "常见原因是账户余额不足、当前能力不可用或密钥权限不足。本次未消耗回合，也没有改变命卷。",
                    List.of("检查对应账户余额与权限", "重新验证或更换自己的密钥"), false,
                    false, false, Map.of(), "personal model request rejected", failure)
                    : new GameApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_GENERATION_UNAVAILABLE", "SYSTEM",
                    "推演服务暂时不可用", "平台推演配置当前需要维护，本次未消耗回合，也没有改变命卷。",
                    List.of("稍后重新尝试", "留意后续服务通知"), true,
                    false, false, Map.of(), "platform model request rejected", failure);
            case RATE_LIMITED -> new GameApiException(HttpStatus.BAD_GATEWAY, "GENERATION_RATE_LIMITED", "SYSTEM",
                    "当前推演请求较多", "服务暂时没有完成这一轮，本次未消耗回合，也没有改变命卷。",
                    List.of("稍等片刻后重试"), true, false, false, Map.of(),
                    "model request rate limited", failure);
            case CONTENT_FILTERED -> new GameApiException(HttpStatus.BAD_REQUEST, "STORY_CONTENT_FILTERED", "INPUT",
                    "这一轮暂时无法生成", "本轮描述未能通过推演服务检查，命卷没有发生变化。",
                    List.of("换一种更简洁的方式描述行动", "减少可能引起误解的细节"), false,
                    false, false, Map.of(), "model content filtered", failure);
            case TEMPORARY_UNAVAILABLE -> GameApiException.temporary("STORY_GENERATION_UNAVAILABLE",
                    "推演服务暂时没有响应", "本次未消耗回合，也没有改变命卷。",
                    List.of("稍后重新尝试本轮行动"), "model temporarily unavailable", failure);
            case AUTHENTICATION -> new GameApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PLATFORM_GENERATION_UNAVAILABLE", "SYSTEM", "推演服务暂时不可用",
                    "平台推演配置当前需要维护，本次未消耗回合，也没有改变命卷。",
                    List.of("稍后重新尝试", "留意后续服务通知"), true,
                    false, false, Map.of(), "platform model authentication rejected", failure);
        };
    }
}
