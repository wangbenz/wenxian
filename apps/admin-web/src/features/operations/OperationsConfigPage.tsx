import "./OperationsConfigPage.css";

import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Card, Descriptions, Input, InputNumber, Modal, Radio, Typography } from "antd";
import { ArrowsClockwise, FloppyDisk, Users } from "@phosphor-icons/react";
import { adminApi, type ActiveSaveRule, type RegistrationConfig } from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
const { TextArea } = Input;
const number = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;

/** 运营配置按规则分别保存，注册容量和命卷上限不会再与点数或玩家凭证拼成一个请求。 */
export function OperationsConfigPage() {
  const [registration, setRegistration] = useState<RegistrationConfig | null>(null);
  const [saveRule, setSaveRule] = useState<ActiveSaveRule | null>(null);
  const [maxUsers, setMaxUsers] = useState(1000);
  const [limitMessage, setLimitMessage] = useState("");
  const [contactText, setContactText] = useState("");
  const [maximumActiveSaves, setMaximumActiveSaves] = useState(4);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<"registration" | "saves" | "">("");
  const [confirmSaves, setConfirmSaves] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const [nextRegistration, nextSaveRule] = await Promise.all([adminApi.registrationConfig(), adminApi.activeSaveRules()]);
      setRegistration(nextRegistration); setSaveRule(nextSaveRule);
      setMaxUsers(nextRegistration.maxUsers); setLimitMessage(nextRegistration.limitMessage); setContactText(nextRegistration.contactText);
      setMaximumActiveSaves(nextSaveRule.maximumActiveSaves);
    } catch (reason) { setError(errorMessage(reason, "运营配置加载失败")); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const registrationDirty = Boolean(registration && (maxUsers !== registration.maxUsers
    || limitMessage.trim() !== registration.limitMessage || contactText.trim() !== registration.contactText));
  const registrationValid = maxUsers >= 1 && maxUsers <= 100000 && Boolean(limitMessage.trim() && contactText.trim());

  const saveRegistration = async () => {
    if (!registrationDirty || !registrationValid || busy) return;
    setBusy("registration"); setError("");
    try {
      const updated = await adminApi.updateRegistrationConfig(maxUsers, limitMessage.trim(), contactText.trim());
      setRegistration(updated); setMaxUsers(updated.maxUsers); setLimitMessage(updated.limitMessage); setContactText(updated.contactText);
      setNotice("注册规则已生效；已有账号登录不受影响。");
    } catch (reason) { setError(errorMessage(reason, "注册规则保存失败")); }
    finally { setBusy(""); }
  };

  const saveActiveSaves = async () => {
    if (!saveRule || maximumActiveSaves === saveRule.maximumActiveSaves || busy) return;
    setBusy("saves"); setError("");
    try {
      const updated = await adminApi.updateActiveSaveRules(maximumActiveSaves);
      setSaveRule(updated); setMaximumActiveSaves(updated.maximumActiveSaves); setConfirmSaves(false);
      setNotice(`同账号可用命卷上限已调整为 ${updated.maximumActiveSaves}；玩家现有命卷没有被修改。`);
    } catch (reason) { setError(errorMessage(reason, "命卷上限保存失败")); setConfirmSaves(false); }
    finally { setBusy(""); }
  };

  return <div className="operations-config-page">
    {notice && <Alert closable onClose={() => setNotice("")} showIcon title={notice} type="success" />}
    {error && <Alert action={<Button onClick={() => void load()} size="small">重新读取</Button>} closable description={error} onClose={() => setError("")} showIcon title="运营配置操作未完成" type="error" />}
    <section className="operations-config-intro"><div><Title level={2}>运营配置</Title><Paragraph>管理注册上限和每个账号可用的命卷数量，两类规则分别保存。</Paragraph></div><Button icon={<ArrowsClockwise size={17} />} loading={loading} onClick={() => void load()}>刷新</Button></section>

    {registration && saveRule && <section className="operations-config-metrics">
      <div><span>当前注册数</span><strong>{number(registration.currentUsers)}</strong><small>已有账号</small></div>
      <div><span>剩余名额</span><strong>{number(registration.remainingSlots)}</strong><small>{registration.remainingSlots > 0 ? "仍可注册" : "注册已暂停"}</small></div>
      <div><span>可用命卷</span><strong>{number(saveRule.activeSaveCount)}</strong><small>全部账号合计</small></div>
      <div><span>多命卷账号</span><strong>{number(saveRule.usersWithMultipleSaves)}</strong><small>当前拥有两份及以上</small></div>
    </section>}

    <section className="operations-config-grid">
      <Card loading={loading} title="新账号注册规则" variant="borderless">
        {registration && <div className="operations-config-form">
          <label><Text strong>最大注册账号数</Text><InputNumber max={100000} min={1} onChange={(value) => setMaxUsers(Number(value || 0))} precision={0} value={maxUsers} /></label>
          <label><Text strong>达到上限时的提示</Text><TextArea maxLength={220} onChange={(event) => setLimitMessage(event.currentTarget.value)} rows={4} showCount value={limitMessage} /></label>
          <label><Text strong>联系与反馈说明</Text><TextArea maxLength={220} onChange={(event) => setContactText(event.currentTarget.value)} rows={4} showCount value={contactText} /></label>
          <Alert description="达到上限后只暂停新账号注册，已有玩家仍可登录并继续命卷。" showIcon title="不会影响已有账号" type="info" />
          <div className="operations-config-actions"><Button disabled={!registrationDirty} onClick={() => { setMaxUsers(registration.maxUsers); setLimitMessage(registration.limitMessage); setContactText(registration.contactText); }}>撤销修改</Button><Button disabled={!registrationDirty || !registrationValid} icon={<FloppyDisk size={17} />} loading={busy === "registration"} onClick={() => void saveRegistration()} type="primary">保存注册规则</Button></div>
        </div>}
      </Card>

      <Card loading={loading} title="同账号可用命卷" variant="borderless">
        {saveRule && <div className="operations-config-form">
          <div className="active-save-heading"><Users size={28} weight="duotone" /><div><Text strong>每个账号最多保留</Text><Paragraph>这里限制同时可用的命卷数量，不限制历史归档记录。</Paragraph></div></div>
          <Radio.Group buttonStyle="solid" onChange={(event) => setMaximumActiveSaves(event.target.value)} value={maximumActiveSaves}>{[1, 2, 3, 4].map((value) => <Radio.Button key={value} value={value}>{value} 份</Radio.Button>)}</Radio.Group>
          <Alert description="如果已有玩家超过新上限，本次调整将无法保存；现有命卷不会自动删除或归档。" showIcon title="调低前会检查现有数据" type="warning" />
          <Descriptions column={1} size="small"><Descriptions.Item label="当前规则">每个账号最多 {saveRule.maximumActiveSaves} 份可用命卷</Descriptions.Item><Descriptions.Item label="当前数据">{number(saveRule.activeSaveCount)} 份可用命卷，涉及 {number(saveRule.usersWithMultipleSaves)} 个多命卷账号</Descriptions.Item><Descriptions.Item label="保存失败时">现有规则和玩家数据都不会改变</Descriptions.Item></Descriptions>
          <div className="operations-config-actions"><Button disabled={maximumActiveSaves === saveRule.maximumActiveSaves} onClick={() => setMaximumActiveSaves(saveRule.maximumActiveSaves)}>撤销修改</Button><Button disabled={maximumActiveSaves === saveRule.maximumActiveSaves} loading={busy === "saves"} onClick={() => setConfirmSaves(true)} type="primary">保存命卷上限</Button></div>
        </div>}
      </Card>
    </section>

    <Modal cancelText="返回修改" confirmLoading={busy === "saves"} okText="确认保存" onCancel={() => !busy && setConfirmSaves(false)} onOk={() => void saveActiveSaves()} open={confirmSaves} title="确认调整同账号命卷上限？">
      <Alert description={`每个账号的可用命卷上限将从 ${saveRule?.maximumActiveSaves ?? "—"} 份调整为 ${maximumActiveSaves} 份。如果已有玩家超过新上限，本次调整将无法保存，现有命卷不会改变。`} showIcon title="请确认新的命卷上限" type="warning" />
    </Modal>
  </div>;
}
