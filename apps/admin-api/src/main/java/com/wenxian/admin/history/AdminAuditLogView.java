package com.wenxian.admin.history;

import java.time.Instant;

/** 管理操作审计列表投影；稳定 ID 用于分页表格定位，详情仍保持不可变。 */
public record AdminAuditLogView(String id, String operation, String detail, Instant at) { }
