import "./SystemPages.css";

import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Card, Statistic, Tag, Typography } from "antd";
import { ArrowsClockwise, CheckCircle, Cpu, Database, Heartbeat, Warning } from "@phosphor-icons/react";
import { adminApi, type AiUsageAnalytics, type AnalyticsOverview, type GenerationProvider } from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
type StatusData = {
  version: string;
  overview: AnalyticsOverview;
  providers: GenerationProvider[];
  aiUsage: AiUsageAnalytics;
};
const fmt = (value: string) => new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value));
const errorMessage = (reason: unknown) => reason instanceof Error ? reason.message : "运行状态读取失败";

/** 运行状态页只汇总当前依赖信号，不承担历史问题查询或配置修改。 */
export function SystemStatusPage() {
  const [data, setData] = useState<StatusData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [version, overview, providers, aiUsage] = await Promise.all([
        adminApi.appVersion(), adminApi.analyticsOverview({ days: 2 }),
        adminApi.generationProviders(), adminApi.analyticsAiUsage({ days: 2 }),
      ]);
      setData({ version: version.version, overview, providers, aiUsage });
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const age = data ? Date.now() - new Date(data.overview.calculatedAt).getTime() : 0;
  const enabledProviders = data?.providers.filter((provider) => provider.enabled).length ?? 0;
  const enabledModels = data?.providers.flatMap((provider) => provider.models).filter((model) => model.platformEnabled).length ?? 0;
  const failedModels = data?.providers.flatMap((provider) => provider.models).filter((model) => model.verificationStatus === "FAILED" || model.verificationStatus === "STALE").length ?? 0;
  const modelRequests = data?.aiUsage.models.reduce((sum, row) => sum + row.requests, 0) ?? 0;
  const modelFailures = data?.aiUsage.models.reduce((sum, row) => sum + row.failures, 0) ?? 0;
  const healthy = Boolean(data) && age <= 15 * 60_000 && failedModels === 0;

  return <div className="system-page">
    <section className="system-intro">
      <div><Title level={2}>运行状态</Title><Paragraph>检查管理台连接、数据更新时间和模型配置。历史问题可前往“玩家问题”查询。</Paragraph></div>
      <Button icon={<ArrowsClockwise size={17} />} loading={loading} onClick={() => void load()}>重新检查</Button>
    </section>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon title="运行状态暂时无法读取" type="error" />}
    <section className="system-status-banner">
      {healthy ? <CheckCircle size={30} weight="fill" /> : <Warning size={30} weight="fill" />}
      <div><Text strong>{healthy ? "当前运行正常" : "有状态需要留意"}</Text><Text type="secondary">模型是否可用，以最近一次实际验证结果为准。</Text></div>
      <Tag color={healthy ? "success" : "warning"}>{healthy ? "正常" : "需检查"}</Tag>
    </section>
    <section className="system-metrics">
      <Card className="system-version-metric"><Text type="secondary">当前游戏版本</Text><strong title={data?.version}>{data?.version || "同步中"}</strong><Database aria-hidden size={20} /></Card>
      <Card><Statistic prefix={<Heartbeat size={20} />} title="统计数据" value={data ? age <= 15 * 60_000 ? "及时" : "延迟" : "—"} /></Card>
      <Card><Statistic prefix={<Cpu size={20} />} suffix={`/ ${data?.providers.length ?? 0}`} title="启用供应商" value={enabledProviders} /></Card>
      <Card><Statistic suffix="个" title="启用模型" value={enabledModels} /></Card>
    </section>
    <div className="system-status-grid">
      <Card title="检查结果">
        <div className="system-check-list">
          <p><span>管理台连接</span><Tag color={data ? "success" : "default"}>{data ? "正常" : "等待"}</Tag><small>{data ? "数据读取正常" : "正在读取数据"}</small></p>
          <p><span>统计汇总</span><Tag color={data && age <= 15 * 60_000 ? "success" : "warning"}>{data && age <= 15 * 60_000 ? "及时" : "延迟"}</Tag><small>{data ? `更新于 ${fmt(data.overview.calculatedAt)}` : "—"}</small></p>
          <p><span>模型验证</span><Tag color={failedModels ? "warning" : "success"}>{failedModels ? `${failedModels} 个需复验` : "全部有效"}</Tag><small>{failedModels ? "重新验证后才能用于新方案" : "当前验证均有效"}</small></p>
          <p><span>近两日模型调用</span><Tag color={modelFailures ? "warning" : "blue"}>{modelRequests} 次请求</Tag><small>{modelFailures ? `${modelFailures} 次最终失败` : "没有统计到最终失败"}</small></p>
        </div>
      </Card>
      <Card title="判断说明">
        <div className="system-boundaries">
          <p><strong>模型可用性</strong><span>启用配置后仍需完成一次实际验证。</span></p>
          <p><strong>统计延迟</strong><span>超过 15 分钟表示聚合可能滞后，不代表玩家命卷异常。</span></p>
          <p><strong>历史问题</strong><span>完整记录请前往“玩家中心 → 问题查询”。</span></p>
          <p><strong>修改配置</strong><span>请前往对应业务页面操作。</span></p>
        </div>
      </Card>
    </div>
  </div>;
}
