package com.wenxian.admin.point;

import com.wenxian.admin.audit.AdminOperationLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 推演点规则应用服务，只编排“未建账账号的期初赠送规则”。
 *
 * <p>三档价格属于版本化服务方案，人工余额属于玩家推演点账户，均不在本用例中修改。
 * 配置更新与视图回读由同一事务编排；审计故障按日志仓储策略降级，不会伪装成规则保存失败。
 * 已经建立的批次和流水永远不会被重新定价。</p>
 */
@Service
public class PointRuleApplicationService {
    private final PointRuleAdminRepository repository;
    private final AdminOperationLogRepository auditLogs;

    public PointRuleApplicationService(PointRuleAdminRepository repository,
                                       AdminOperationLogRepository auditLogs) {
        this.repository = repository;
        this.auditLogs = auditLogs;
    }

    public PointRuleView rule() {
        return view(repository.state());
    }

    @Transactional
    public PointRuleView update(int openingGrant, String depletedMessage, String actor) {
        if (openingGrant < 1 || openingGrant > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "期初赠送需在 1 至 1000 推演点之间");
        }
        String safeMessage = depletedMessage == null ? "" : depletedMessage.strip();
        if (safeMessage.isEmpty() || safeMessage.length() > 220) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "点数耗尽提示需填写且不能超过 220 个字符");
        }
        repository.updateOpeningRule(openingGrant, safeMessage);
        auditLogs.append("UPDATE_POINT_RULE", "管理员 " + safeActor(actor) + " 将未建账账号期初赠送调整为 "
                + openingGrant + " 推演点，并更新点数耗尽提示；既有批次不重算");
        return rule();
    }

    private PointRuleView view(PointRuleAdminRepository.PointRuleState state) {
        return new PointRuleView(state.openingGrant(), state.depletedMessage(), state.totalAccounts(),
                state.initializedAccounts(), Math.max(0, state.totalAccounts() - state.initializedAccounts()),
                state.grantedPoints(), state.availablePoints(), state.reservedPoints(), state.consumedPoints(),
                true);
    }

    private String safeActor(String actor) {
        return actor == null || actor.isBlank() ? "admin-console" : actor.strip();
    }
}
