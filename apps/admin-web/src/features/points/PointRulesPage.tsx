import "./PointRulesPage.css";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, Descriptions, Empty, Input, InputNumber, Modal, Space, Tag, Typography } from "antd";
import { ArrowRight, ArrowsClockwise, Coins, FloppyDisk, ShieldCheck } from "@phosphor-icons/react";
import { useNavigate } from "react-router-dom";
import { adminApi, type PointRule, type ServicePlan, type ServicePlanTier } from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
const { TextArea } = Input;
type TierCode = ServicePlanTier["tierCode"];

const tierNames: Record<TierCode, { name: string; code: string }> = {
  LIGHT: { name: "轻量", code: "LIGHT" },
  BALANCED: { name: "均衡", code: "BALANCED" },
  DEEP: { name: "深度", code: "DEEP" },
};
const number = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;

/**
 * 点数规则页只编辑新账本期初赠送，不复制三档价格编辑器，也不允许改既有玩家余额。
 * 这样未来增加购买、活动或退款规则时仍能按规则域扩展，而不会重新形成旧综合设置页。
 */
export function PointRulesPage() {
  const navigate = useNavigate();
  const [rule, setRule] = useState<PointRule | null>(null);
  const [plans, setPlans] = useState<ServicePlan[]>([]);
  const [openingGrant, setOpeningGrant] = useState(20);
  const [depletedMessage, setDepletedMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [nextRule, nextPlans] = await Promise.all([adminApi.pointRules(), adminApi.servicePlans()]);
      setRule(nextRule); setPlans(nextPlans);
      setOpeningGrant(nextRule.openingGrant); setDepletedMessage(nextRule.depletedMessage);
    } catch (reason) { setError(errorMessage(reason, "点数规则加载失败")); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const activePlan = plans.find((plan) => plan.status === "ACTIVE") ?? null;
  const tiers = useMemo(() => activePlan?.tiers
    .slice().sort((left, right) => ["LIGHT", "BALANCED", "DEEP"].indexOf(left.tierCode)
      - ["LIGHT", "BALANCED", "DEEP"].indexOf(right.tierCode)) ?? [], [activePlan]);
  const dirty = Boolean(rule && (openingGrant !== rule.openingGrant || depletedMessage.trim() !== rule.depletedMessage));
  const valid = openingGrant >= 1 && openingGrant <= 1000 && depletedMessage.trim().length > 0
    && depletedMessage.trim().length <= 220;

  const save = async () => {
    if (!valid || !dirty || busy) return;
    setBusy(true); setError("");
    try {
      const updated = await adminApi.updatePointRules(openingGrant, depletedMessage.trim());
      setRule(updated); setOpeningGrant(updated.openingGrant); setDepletedMessage(updated.depletedMessage);
      setNotice(`期初赠送已调整为 ${number(updated.openingGrant)} 推演点；已建账玩家余额没有重算。`);
      setConfirmOpen(false);
    } catch (reason) { setError(errorMessage(reason, "点数规则保存失败")); }
    finally { setBusy(false); }
  };

  return <div className="point-rules-page">
    {notice && <Alert closable onClose={() => setNotice("")} showIcon title={notice} type="success" />}
    {error && <Alert action={<Button onClick={() => void load()} size="small">重新读取</Button>} closable description={error} onClose={() => setError("")} showIcon title="点数规则操作未完成" type="error" />}
    <section className="point-rules-intro">
      <div><Title level={2}>点数规则</Title><Paragraph>设置新账号的初始点数，并查看三档价格和扣点规则。玩家余额与人工赠送请前往“推演点账户”。</Paragraph></div>
      <Space wrap><Button icon={<ArrowsClockwise size={17} />} loading={loading} onClick={() => void load()}>刷新</Button><Button icon={<Coins size={17} />} onClick={() => navigate("/players/points")}>玩家账户</Button></Space>
    </section>

    <Alert className="point-rules-compatibility" description="已有点数不会重新计算；尚未领取初始点数的账号，会按当时生效的规则领取。" showIcon title="修改后只影响尚未领取初始点数的账号" type="info" />

    {rule && <section className="point-rules-metrics" aria-label="推演点账本概况">
      <div><span>已有点数账户</span><strong>{number(rule.initializedAccounts)}</strong><small>共 {number(rule.totalAccounts)} 个账号</small></div>
      <div><span>尚未领取初始点数</span><strong>{number(rule.awaitingInitializationAccounts)}</strong><small>首次使用时发放</small></div>
      <div><span>当前可用</span><strong>{number(rule.availablePoints)}</strong><small>已建账玩家合计</small></div>
      <div><span>使用中</span><strong>{number(rule.reservedPoints)}</strong><small>成功后扣除，失败则返还</small></div>
      <div><span>累计扣除</span><strong>{number(rule.consumedPoints)}</strong><small>只统计成功回合</small></div>
    </section>}

    <section className="point-rules-grid">
      <Card className="point-opening-rule" loading={loading} title="新账号初始点数" variant="borderless">
        {rule ? <>
          <label><Text strong>首次发放数量</Text><InputNumber max={1000} min={1} onChange={(value) => setOpeningGrant(Number(value || 0))} precision={0} suffix="推演点" value={openingGrant} /></label>
          <label><Text strong>点数耗尽提示</Text><TextArea maxLength={220} onChange={(event) => setDepletedMessage(event.currentTarget.value)} rows={5} showCount value={depletedMessage} /></label>
          <Alert description="保存后不会自动补发或扣除已有玩家的点数。需要调整个人余额时，请前往“推演点账户”。" showIcon title="已有余额不受影响" type="info" />
          <div className="point-rule-actions"><Button disabled={!dirty} onClick={() => { setOpeningGrant(rule.openingGrant); setDepletedMessage(rule.depletedMessage); }}>撤销修改</Button><Button disabled={!dirty || !valid} icon={<FloppyDisk size={17} />} onClick={() => setConfirmOpen(true)} type="primary">保存期初规则</Button></div>
        </> : !loading && <Empty description="没有读取到点数规则" />}
      </Card>

      <Card className="point-settlement-policy" title="扣点规则" variant="borderless">
        <div className="point-settlement-flow"><div><span>1</span><strong>开始推演</strong><small>按所选档位暂时保留对应点数</small></div><ArrowRight size={18} /><div><span>2</span><strong>模型生成</strong><small>重试或切换备用模型不会重复扣点</small></div><ArrowRight size={18} /><div><span>3</span><strong>完成结算</strong><small>成功保存后扣点，失败时全部返还</small></div></div>
        <Descriptions className="point-policy-descriptions" column={1} size="small">
          <Descriptions.Item label="游戏回合">一次玩家剧情行动仍算一个回合，不受档位点数影响</Descriptions.Item>
          <Descriptions.Item label="三档价格">以回合开始时生效的三档方案为准</Descriptions.Item>
          <Descriptions.Item label="玩家自备服务">成功使用自备 Key 的回合不消耗平台推演点</Descriptions.Item>
          <Descriptions.Item label="历史记录">已完成的扣点记录不会按新价格重新计算</Descriptions.Item>
        </Descriptions>
        <div className="point-policy-seal"><ShieldCheck size={24} weight="duotone" /><span><strong>每次变动都有记录</strong><small>发放、扣除、返还和撤销都可在玩家账户中查询</small></span></div>
      </Card>
    </section>

    <Card className="point-tier-prices" extra={<Button icon={<ArrowRight size={16} />} onClick={() => navigate("/inference/plans")} type="link">前往三档方案</Button>} title="当前三档价格" variant="borderless">
      {!activePlan ? <Empty description="还没有已发布的三档方案；点数期初规则可先保存，但玩家尚无三档入口" /> : <>
        <div className="point-tier-heading"><Text>当前方案 v{activePlan.versionNumber}</Text><Tag color={activePlan.gameRuntimeApplied ? "success" : "warning"}>{activePlan.gameRuntimeApplied ? "已生效" : "尚未启用"}</Tag></div>
        <div className="point-tier-grid">{tiers.map((tier) => <article className={`point-tier-card point-tier-${tier.tierCode.toLowerCase()}`} key={tier.tierCode}>
          <div><span>{tierNames[tier.tierCode].code}</span><Tag color={tier.enabled ? "success" : "default"}>{tier.enabled ? "已启用" : "已停用"}</Tag></div>
          <strong>{tier.displayName || tierNames[tier.tierCode].name}</strong>
          <b>{number(tier.pointCost)}<small> 点 / 正式回合</small></b>
          <p>{tier.description || "尚未填写玩家说明"}</p>
          <footer><span>使用模型</span><code>{tier.primaryProviderCode && tier.primaryModelCode ? `${tier.primaryProviderCode}/${tier.primaryModelCode}` : "未配置"}</code></footer>
        </article>)}</div>
      </>}
    </Card>

    <Modal cancelText="返回修改" className="point-rule-confirm" confirmLoading={busy} okText="确认保存" onCancel={() => !busy && setConfirmOpen(false)} onOk={() => void save()} open={confirmOpen} title="确认调整期初赠送规则？">
      {rule && <Descriptions bordered column={1} size="small"><Descriptions.Item label="初始点数">{number(rule.openingGrant)} → {number(openingGrant)} 推演点</Descriptions.Item><Descriptions.Item label="影响账号">{number(rule.awaitingInitializationAccounts)} 个尚未领取初始点数的账号</Descriptions.Item><Descriptions.Item label="不会变化">{number(rule.initializedAccounts)} 个已有点数账户的余额和记录</Descriptions.Item><Descriptions.Item label="耗尽提示">{depletedMessage.trim()}</Descriptions.Item></Descriptions>}
    </Modal>
  </div>;
}
