package com.wenxian.admin.history;

import java.time.Instant;

/** 玩家问题历史投影；diagnostic 仅在受鉴权的管理台展示，不进入玩家接口。 */
public record OperationProblemView(String referenceId, String saveId, String operation,
                                   String category, String code, int httpStatus,
                                   String message, String diagnostic, String email,
                                   String playerName, Instant at) { }
