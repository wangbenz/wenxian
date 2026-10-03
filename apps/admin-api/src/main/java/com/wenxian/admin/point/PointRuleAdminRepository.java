package com.wenxian.admin.point;

/** 推演点规则的持久化端口；只暴露期初赠送规则和只读账本汇总，不允许直接改玩家余额。 */
public interface PointRuleAdminRepository {
    PointRuleState state();

    void updateOpeningRule(int openingGrant, String depletedMessage);

    record PointRuleState(int openingGrant, String depletedMessage, long totalAccounts,
                          long initializedAccounts, long grantedPoints, long availablePoints,
                          long reservedPoints, long consumedPoints) { }
}
