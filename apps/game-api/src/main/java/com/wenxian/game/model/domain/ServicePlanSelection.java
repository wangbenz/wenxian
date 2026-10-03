package com.wenxian.game.model.domain;

import java.util.Locale;

/**
 * 一次平台推演请求冻结的方案、档位和价格。
 *
 * <p>该值在任何模型调用和推演点预占前生成；同一请求的导演、正文、重试、回退与最终结算
 * 必须复用同一份值，不能重新读取后来发布的方案。</p>
 */
public record ServicePlanSelection(String planVersionId, String tierCode, int pointCost) {
    public ServicePlanSelection {
        if (planVersionId == null || planVersionId.isBlank()) {
            throw new IllegalArgumentException("推演服务方案版本不能为空");
        }
        planVersionId = planVersionId.strip();
        if (tierCode == null || tierCode.isBlank()) {
            throw new IllegalArgumentException("推演服务档位不能为空");
        }
        tierCode = tierCode.strip().toUpperCase(Locale.ROOT);
        if (pointCost <= 0) throw new IllegalArgumentException("推演点价格必须大于0");
    }
}
