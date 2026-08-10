package com.wenxian.game.story.application;

import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.analytics.AnalyticsEventType;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.engine.StoryEngineRegistry;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionConfirmRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionPreviewRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionPreviewResponse;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 统一命卷用例门面。
 *
 * <p>该服务只做跨引擎路由和列表聚合，不直接访问数据库，也不修改引擎生成结果。
 * 单份命卷的事务边界始终由被选中的故事引擎维护。</p>
 */
@Service
public class StoryApplicationService {
    private final StoryEngineRegistry engines;
    private final AnalyticsEventPublisher analytics;
    private final StoryCorrectionPreviewService correctionPreviews;
    private final StoryCorrectionCommitService correctionCommits;

    public StoryApplicationService(StoryEngineRegistry engines, AnalyticsEventPublisher analytics,
                                   StoryCorrectionPreviewService correctionPreviews,
                                   StoryCorrectionCommitService correctionCommits) {
        this.engines = engines;
        this.analytics = analytics;
        this.correctionPreviews = correctionPreviews;
        this.correctionCommits = correctionCommits;
    }

    public BootstrapResponse bootstrap() {
        return engines.defaultEngine().bootstrap();
    }

    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        return engines.defaultEngine().characterRoutes(user, request);
    }

    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        SaveResponse created = engines.defaultEngine().create(user, request);
        analytics.publish("SAVE_CREATED:" + created.saveId(), AnalyticsEventType.SAVE_CREATED,
                user.userId(), created.saveId(), created.engineCode(), Map.of("worldCode", request.worldCode()));
        return created;
    }

    public List<SaveSummary> list(UserIdentity user) {
        return engines.all().stream().flatMap(engine -> engine.list(user).stream())
                .sorted(Comparator.comparing(SaveSummary::updatedAt).reversed())
                .toList();
    }

    public SaveResponse get(UserIdentity user, String saveId) {
        return engines.requireForSave(saveId).get(user, saveId);
    }

    public SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request) {
        SaveResponse updated = engines.requireForSave(saveId).takeTurn(user, saveId, request);
        analytics.publish("TURN_SUCCEEDED:" + saveId + ":" + request.requestId(),
                AnalyticsEventType.TURN_SUCCEEDED, user.userId(), saveId, updated.engineCode(),
                Map.of("turnNumber", updated.turnNumber(), "generationMode", safe(updated.generationMode())));
        return updated;
    }

    public SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request) {
        SaveResponse updated = engines.requireForSave(saveId).correct(user, saveId, request);
        analytics.publish("CORRECTION_APPLIED:" + saveId + ":" + updated.revision(),
                AnalyticsEventType.CORRECTION_APPLIED, user.userId(), saveId, updated.engineCode(), Map.of());
        return updated;
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
            SaveResponse updated = engines.requireForSave(saveId).get(user, saveId);
            analytics.publish("CORRECTION_APPLIED:" + saveId + ":" + committed.revision(),
                    AnalyticsEventType.CORRECTION_APPLIED, user.userId(), saveId, updated.engineCode(),
                    Map.of("correctionKind", committed.acceptedProposal().kind().name()));
            return updated;
        } catch (StoryCorrectionException error) {
            throw correctionFailure(error);
        }
    }

    public void archive(UserIdentity user, String saveId) {
        var engine = engines.requireForSave(saveId);
        engine.archive(user, saveId);
        analytics.publish("SAVE_ARCHIVED:" + saveId, AnalyticsEventType.SAVE_ARCHIVED,
                user.userId(), saveId, engine.descriptor().code(), Map.of());
    }

    public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) {
        SaveResponse updated = engines.requireForSave(saveId).removeEntity(user, saveId, entityId, expectedRevision);
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
                    "SAVE_NOT_ACTIVE", "STATE", "这份命卷已经封存", error.getMessage(),
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
