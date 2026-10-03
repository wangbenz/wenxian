package com.wenxian.admin.point;

/** 推演点规则页的只读投影；compatibilityStorage 表示当前仍复用旧配置键，不代表三档运行时已切换。 */
public record PointRuleView(int openingGrant, String depletedMessage, long totalAccounts,
                            long initializedAccounts, long awaitingInitializationAccounts,
                            long grantedPoints, long availablePoints, long reservedPoints,
                            long consumedPoints, boolean compatibilityStorage) { }
