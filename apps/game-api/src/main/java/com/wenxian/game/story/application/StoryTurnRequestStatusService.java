package com.wenxian.game.story.application;

import com.wenxian.game.credit.TurnCreditModels.ReservationStatus;
import com.wenxian.game.credit.TurnCreditRepository;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 汇总一次命卷行动的持久化状态，供 H5 在网络超时后恢复原请求。
 *
 * <p>不可变回合是“已经成功”的唯一权威；诊断和额度预占只用于判断后台仍在处理或已经失败。
 * 本服务只读，不重试模型、不提交命卷，也不修改回合余额。</p>
 */
@Service
public class StoryTurnRequestStatusService {
    private static final int RETRY_AFTER_MS = 2500;

    private final StoryTurnRepository turns;
    private final StoryTurnDiagnosticRepository diagnostics;
    private final TurnCreditRepository credits;

    public StoryTurnRequestStatusService(StoryTurnRepository turns,
                                         StoryTurnDiagnosticRepository diagnostics,
                                         TurnCreditRepository credits) {
        this.turns = turns;
        this.diagnostics = diagnostics;
        this.credits = credits;
    }

    /** 查询请求状态；调用方必须先校验命卷归属。 */
    public Result query(String userId, String saveId, String requestId) {
        var committed = turns.findByRequestId(saveId, requestId);
        if (committed.isPresent()) {
            return new Result(Status.COMMITTED, true, false, 0,
                    "本回合已经写入命卷。", committed.get().createdAt());
        }

        var diagnostic = diagnostics.findRequestState(saveId, requestId);
        if (diagnostic.isPresent()) {
            var value = diagnostic.get();
            if ("FAILED".equals(value.status())) {
                return new Result(Status.FAILED, true, true, 0,
                        "本次推演没有写入命卷，可以重新提交行动。", value.updatedAt());
            }
            return new Result(Status.PROCESSING, false, false, RETRY_AFTER_MS,
                    "命卷仍在推演，请稍候。", value.updatedAt());
        }

        var reservation = credits.findReservation(userId, saveId, requestId);
        if (reservation.isPresent()) {
            var value = reservation.get();
            if (value.status() == ReservationStatus.RELEASED || value.status() == ReservationStatus.EXPIRED) {
                return new Result(Status.FAILED, true, true, 0,
                        "本次推演没有写入命卷，可以重新提交行动。", value.updatedAt());
            }
            return new Result(Status.PROCESSING, false, false, RETRY_AFTER_MS,
                    "命卷仍在推演，请稍候。", value.updatedAt());
        }

        return new Result(Status.UNKNOWN, false, true, RETRY_AFTER_MS,
                "尚未找到这次推演记录，正在继续确认。", Instant.now());
    }

    public enum Status {
        PROCESSING,
        COMMITTED,
        FAILED,
        UNKNOWN
    }

    /** 应用层使用的中性状态，不包含命卷快照和内部失败详情。 */
    public record Result(Status status, boolean terminal, boolean retryable,
                         int retryAfterMs, String message, Instant updatedAt) {
    }
}
