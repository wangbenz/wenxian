package com.wenxian.game.model.infrastructure;

import com.wenxian.game.credit.PlatformTurnQuotaService;
import com.wenxian.game.model.application.ModelInvocationAuthorizer;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 在创角草案请求前复用现有平台回合访问边界。
 *
 * <p>创角路线不会消费回合，但平台额度已经耗尽时也不能继续产生平台模型费用；
 * 玩家自备密钥不受平台额度限制。</p>
 */
@Component
public class PlatformDraftModelInvocationAuthorizer implements ModelInvocationAuthorizer {
    private static final Set<ModelTask> DRAFT_TASKS = Set.of(ModelTask.CHARACTER_CREATION);

    private final PlatformTurnQuotaService quota;

    public PlatformDraftModelInvocationAuthorizer(PlatformTurnQuotaService quota) {
        this.quota = quota;
    }

    @Override
    public void authorize(ModelRouteRequest request, ModelRoute route) {
        if (!DRAFT_TASKS.contains(request.task())
                || route.primary().credential().source() != CredentialSource.PLATFORM) return;
        quota.assertPlatformQuota(request.userId());
    }
}
