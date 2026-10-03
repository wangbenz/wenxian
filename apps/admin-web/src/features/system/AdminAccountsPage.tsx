import "./SystemPages.css";

import { useCallback, useEffect, useState } from "react";
import { Alert, Button, Card, Empty, Form, Input, Modal, Space, Table, Tag, Typography, type TableColumnsType } from "antd";
import { Key, Plus, ShieldCheck } from "@phosphor-icons/react";
import { adminApi, type AdminAccount } from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
type CreateAdminForm = { username: string; password: string; confirmPassword: string };
const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "尚未登录";
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;

/** 管理账号页集中处理身份类高风险操作；当前账号不能自我停用或重置，所有变更继续由后端写入审计。 */
export function AdminAccountsPage() {
  const [accounts, setAccounts] = useState<AdminAccount[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState("");
  const [form] = Form.useForm<CreateAdminForm>();
  const [modal, contextHolder] = Modal.useModal();

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setAccounts(await adminApi.adminAccounts()); }
    catch (reason) { setError(errorMessage(reason, "管理账号加载失败")); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const create = async () => {
    setCreateError("");
    try {
      const values = await form.validateFields();
      setCreating(true);
      await adminApi.createAdminAccount(values.username.trim(), values.password);
      setCreateOpen(false); form.resetFields(); setNotice(`管理账号 ${values.username.trim()} 已创建，可以立即登录。`);
      await load();
    } catch (reason) {
      if (reason instanceof Error) setCreateError(reason.message);
    } finally { setCreating(false); }
  };
  const resetPassword = (account: AdminAccount) => modal.confirm({
    title: `重置 ${account.username} 的管理密码？`,
    content: "重置后该账号的全部会话都会失效，临时密码只展示本次。",
    okText: "确认重置", cancelText: "取消", okButtonProps: { danger: true },
    onOk: async () => {
      const result = await adminApi.resetAdminAccountPassword(account.id);
      setTemporaryPassword(result.temporaryPassword); setNotice(`账号 ${account.username} 的密码已重置。`); await load();
    },
  });
  const updateStatus = (account: AdminAccount) => {
    const disabling = account.status === "ACTIVE";
    modal.confirm({
      title: `${disabling ? "停用" : "启用"}管理账号 ${account.username}？`,
      content: disabling ? "停用后该账号会立即退出且无法登录。" : "启用后该账号可重新登录管理台。",
      okText: disabling ? "确认停用" : "确认启用", cancelText: "取消", okButtonProps: { danger: disabling },
      onOk: async () => { await adminApi.updateAdminAccountStatus(account.id, !disabling); setNotice(`管理账号 ${account.username} 已${disabling ? "停用" : "启用"}。`); await load(); },
    });
  };

  const columns: TableColumnsType<AdminAccount> = [
    { title: "管理账号", dataIndex: "username", width: 220, render: (value: string, row) => <Space><ShieldCheck size={20} weight="duotone" /><Text strong>{value}</Text>{row.current && <Tag color="blue">当前账号</Tag>}</Space> },
    { title: "状态", dataIndex: "status", width: 110, render: (value: AdminAccount["status"]) => <Tag color={value === "ACTIVE" ? "success" : "default"}>{value === "ACTIVE" ? "可用" : "已停用"}</Tag> },
    { title: "创建时间", dataIndex: "createdAt", width: 180, render: fmt },
    { title: "最近登录", dataIndex: "lastLoginAt", width: 180, render: fmt },
    { title: "有效会话", dataIndex: "activeSessions", width: 100 },
    { title: "操作", key: "action", fixed: "right", width: 190, render: (_, row) => <Space><Button disabled={row.current} onClick={() => resetPassword(row)} size="small">重置密码</Button><Button danger={row.status === "ACTIVE"} disabled={row.current} onClick={() => updateStatus(row)} size="small">{row.status === "ACTIVE" ? "停用" : "启用"}</Button></Space> },
  ];

  return <div className="system-page">
    {contextHolder}
    <section className="system-intro">
      <div><Title level={2}>管理账号</Title><Paragraph>每位运营人员使用独立账号；账号创建、密码重置和停用都会记录。</Paragraph></div>
      <Button icon={<Plus size={17} />} onClick={() => { setCreateError(""); setCreateOpen(true); }} type="primary">新增管理账号</Button>
    </section>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon type="error" />}
    {notice && <Alert closable description={notice} onClose={() => setNotice("")} showIcon type="success" />}
    {temporaryPassword && <Alert action={<Button onClick={() => setTemporaryPassword("")} size="small">已保存</Button>} description={<Text copyable strong>{temporaryPassword}</Text>} icon={<Key size={20} />} showIcon title="本次临时密码，请立即安全交付" type="warning" />}
    <Card className="system-table-card" variant="borderless">
      <Table<AdminAccount> columns={columns} dataSource={accounts} loading={loading} locale={{ emptyText: <Empty description="还没有可见的管理账号" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} pagination={false} rowKey="id" scroll={{ x: 1020 }} />
    </Card>
    <Modal cancelText="取消" confirmLoading={creating} destroyOnHidden onCancel={() => { setCreateOpen(false); form.resetFields(); setCreateError(""); }} onOk={() => void create()} okText="创建账号" open={createOpen} title="新增管理账号">
      <Paragraph>每位运营人员使用独立账号，初始密码请通过安全渠道交付。</Paragraph>
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item label="登录账号" name="username" rules={[{ required: true, message: "请输入登录账号" }, { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: "需为 3-32 位字母、数字、点、横线或下划线" }]}><Input autoComplete="off" maxLength={32} placeholder="例如 operations_01" /></Form.Item>
        <Form.Item label="初始密码" name="password" rules={[{ required: true, message: "请输入初始密码" }, { min: 8, message: "密码不能少于 8 位" }]}><Input.Password autoComplete="new-password" maxLength={72} /></Form.Item>
        <Form.Item dependencies={["password"]} label="再次输入初始密码" name="confirmPassword" rules={[{ required: true, message: "请再次输入密码" }, ({ getFieldValue }) => ({ validator(_, value) { return !value || getFieldValue("password") === value ? Promise.resolve() : Promise.reject(new Error("两次输入的密码不一致")); } })]}><Input.Password autoComplete="new-password" maxLength={72} /></Form.Item>
      </Form>
      {createError && <Alert description={createError} showIcon type="error" />}
    </Modal>
  </div>;
}
