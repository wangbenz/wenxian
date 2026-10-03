package com.wenxian.game.story.application;

import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.analytics.AnalyticsEventType;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.engine.StoryEngine;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CorrectionConfirmRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionPreviewRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionPreviewResponse;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.TurnRequestStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * 统一命卷用例门面。
 *
 * <p>该服务编排唯一的中立故事运行时，不直接访问数据库，也不修改引擎生成结果。
 * 命卷事务边界始终由故事运行时维护；产品版本和世界模板不能借此创建第二套在线引擎。</p>
 */
@Service
public class StoryApplicationService {
    private final StoryEngine engine;
    private final AnalyticsEventPublisher analytics;
    private final StoryCorrectionPreviewService correctionPreviews;
    private final StoryCorrectionCommitService correctionCommits;
    private final StoryTurnRequestStatusService turnRequestStatuses;

    public StoryApplicationService(StoryEngine engine, AnalyticsEventPublisher analytics,
                                   StoryCorrectionPreviewService correctionPreviews,
                                   StoryCorrectionCommitService correctionCommits,
                                   StoryTurnRequestStatusService turnRequestStatuses) {
        this.engine = engine;
        this.analytics = analytics;
        this.correctionPreviews = correctionPreviews;
        this.correctionCommits = correctionCommits;
        this.turnRequestStatuses = turnRequestStatuses;
    }

    public BootstrapResponse bootstrap() {
        return engine.bootstrap();
    }

    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        return engine.characterRoutes(user, request);
    }

    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        SaveResponse created = engine.create(user, request);
        analytics.publish("SAVE_CREATED:" + created.saveId(), AnalyticsEventType.SAVE_CREATED,
                user.userId(), created.saveId(), created.engineCode(),
                Map.of("worldPackageId", request.worldPackageId(), "worldRevision", request.worldRevision()));
        return created;
    }

    public List<SaveSummary> list(UserIdentity user) {
        return engine.list(user);
    }

    public SaveResponse get(UserIdentity user, String saveId) {
        return engine.get(user, saveId);
    }

    public SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request) {
        SaveResponse updated = engine.takeTurn(user, saveId, request);
        analytics.publish("TURN_SUCCEEDED:" + saveId + ":" + request.requestId(),
                AnalyticsEventType.TURN_SUCCEEDED, user.userId(), saveId, updated.engineCode(),
                Map.of("turnNumber", updated.turnNumber(), "generationMode", safe(updated.generationMode())));
        return updated;
    }

    /**
     * 查询一次原始回合请求的最终状态。先通过故事引擎校验命卷归属；只有不可变回合已经存在时才返回最新快照。
     */
    public TurnRequestStatusResponse turnRequestStatus(UserIdentity user, String saveId, String requestId) {
        engine.get(user, saveId);
        var result = turnRequestStatuses.query(user.userId(), saveId, requestId);
        SaveResponse snapshot = result.status() == StoryTurnRequestStatusService.Status.COMMITTED
                ? engine.get(user, saveId) : null;
        return new TurnRequestStatusResponse(requestId, result.status().name(), result.terminal(),
                result.retryable(), result.retryAfterMs(), result.message(), result.updatedAt(), snapshot);
    }

    public CorrectionPreviewResponse previewCorrection(UserIdentity user, String saveId,
                                                       CorrectionPreviewRequest request) {
        try {
            StoryCorrectionWorkflow.Preview preview = correctionPreviews.preview(user.userId(), saveId,
                    request.expectedRevision(), request.instruction());
            return new CorrectionPreviewResponse(preview.kind().name(), preview.explanation(),
                    preview.confirmationPrompt(), preview.proposalToken());
        } catch (StoryCorrectionException error) {
            throw correctionFailure(error);
        }
    }

    public SaveResponse confirmCorrection(UserIdentity user, String saveId, CorrectionConfirmRequest request) {
        try {
            var committed = correctionCommits.confirm(user.userId(), saveId, request.expectedRevision(),
                    request.proposalToken());
            SaveResponse updated = engine.get(user, saveId);
            analytics.publish("CORRECTION_APPLIED:" + saveId + ":" + committed.revision(),
                    AnalyticsEventType.CORRECTION_APPLIED, user.userId(), saveId, updated.engineCode(),
                    Map.of("correctionKind", committed.acceptedProposal().kind().name()));
            return updated;
        } catch (StoryCorrectionException error) {
            throw correctionFailure(error);
        }
    }

    public void deleteSave(UserIdentity user, String saveId) {
        engine.deleteSave(user, saveId);
        analytics.publish("SAVE_DELETED:" + saveId, AnalyticsEventType.SAVE_DELETED,
                user.userId(), saveId, engine.descriptor().code(), Map.of());
    }

    public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) {
        SaveResponse updated = engine.removeEntity(user, saveId, entityId, expectedRevision);
        analytics.publish("ENTITY_REMOVED:" + saveId + ":" + entityId + ":" + updated.revision(),
                AnalyticsEventType.ENTITY_REMOVED, user.userId(), saveId, updated.engineCode(),
                Map.of("entityId", entityId));
        return updated;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private com.wenxian.game.api.GameApiException correctionFailure(StoryCorrectionException error) {
        return switch (error.reason()) {
            case SAVE_NOT_FOUND -> com.wenxian.game.api.GameApiException.of(HttpStatus.NOT_FOUND,
                    "STORY_SAVE_NOT_FOUND", "STATE", "没有找到这份命卷", error.getMessage(),
                    List.of("刷新我的命卷"));
            case SAVE_NOT_ACTIVE -> com.wenxian.game.api.GameApiException.of(HttpStatus.CONFLICT,
                    "SAVE_NOT_ACTIVE", "STATE", "这份命卷已经无法继续", error.getMessage(),
                    List.of("选择其他命卷"));
            case REVISION_CONFLICT -> com.wenxian.game.api.GameApiException.of(HttpStatus.CONFLICT,
                    "CORRECTION_REVISION_CONFLICT", "STATE", "命卷状态已经变化", error.getMessage(),
                    List.of("刷新当前命卷", "重新预览修正"));
            case NO_STATE_CHANGE -> com.wenxian.game.api.GameApiException.of(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CORRECTION_NO_STATE_CHANGE", "STATE", "当前命卷无需修改", error.getMessage(),
                    List.of("直接输入你真正要做的下一步", "如需改动客观事实，请明确说明地点、物品、能力或人物状态"));
            case PROPOSAL_INVALID -> com.wenxian.game.api.GameApiException.of(HttpStatus.CONFLICT,
                    "CORRECTION_PREVIEW_EXPIRED", "STATE", "修正预览已经失效", error.getMessage(),
                    List.of("重新预览修正"));
            case PROPOSAL_REJECTED -> com.wenxian.game.api.GameApiException.of(HttpStatus.CONFLICT,
                    "CORRECTION_REJECTED", "STATE", "这项修正无法应用", error.getMessage(),
                    List.of("刷新命卷后重新说明要修正的事实"));
            case PREVIEW_FAILED -> com.wenxian.game.api.GameApiException.temporary(
                    "CORRECTION_PREVIEW_FAILED", "暂时无法生成修正预览", error.getMessage(),
                    List.of("稍后重试", "换一种更明确的方式说明要修正的事实"),
                    "correction preview model or parser failed", error);
        };
    }
}
