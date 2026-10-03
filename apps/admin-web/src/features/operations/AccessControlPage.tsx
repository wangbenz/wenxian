import "./OperationsManagementPages.css";

import { useCallback, useEffect, useState } from "react";
import {
  Alert, Button, Card, DatePicker, Empty, Form, Input, Modal, Radio, Space,
  Statistic, Table, Tag, TimePicker, Typography, type TableColumnsType,
} from "antd";
import { LockKeyOpen, Plus, ShieldCheck } from "@phosphor-icons/react";
import dayjs, { type Dayjs } from "dayjs";
import { adminApi, type AccessControl } from "../../admin-api";
import { shanghaiToday } from "../analytics/useAnalyticsDateRange";

const { Paragraph, Text, Title } = Typography;
type AccessForm = { mode: AccessControl["mode"]; title: string; message: string; resumeDate: Dayjs | null; resumeTime: Dayjs | null };
type AddForm = { email: string; note?: string };
const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(new Date(value)) : "—";
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;
const shanghaiInstant = (date: Dayjs, time: Dayjs) => new Date(Date.UTC(date.year(), date.month(), date.date(), time.hour() - 8, time.minute())).toISOString();

/** 灰度访问页只控制进入权限和白名单，不修改玩家命卷；切换限制模式前必须明确确认影响范围。 */
export function AccessControlPage() {
  const [data, setData] = useState<AccessControl | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [form] = Form.useForm<AccessForm>();
  const [addForm] = Form.useForm<AddForm>();
  const [modal, contextHolder] = Modal.useModal();
  const mode = Form.useWatch("mode", form);
  const resumeDate = Form.useWatch("resumeDate", form);

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try {
      const result = await adminApi.accessControl(); setData(result);
      const resume = result.resumeAt ? dayjs(result.resumeAt) : null;
      form.setFieldsValue({ mode: result.mode, title: result.title, message: result.message, resumeDate: resume, resumeTime: resume });
    } catch (reason) { setError(errorMessage(reason, "灰度访问规则加载失败")); }
    finally { setLoading(false); }
  }, [form]);
  useEffect(() => { void load(); }, [load]);

  const save = async () => {
    try {
      const values = await form.validateFields();
      if (values.mode === "WHITELIST" && !data?.whitelist.length) return setError("请先加入至少一个测试账号，再开启白名单");
      const execute = async () => {
        setSaving(true); setError("");
        try {
          const resumeAt = values.resumeDate && values.resumeTime ? shanghaiInstant(values.resumeDate, values.resumeTime) : "";
          const result = await adminApi.updateAccessControl({ mode: values.mode, title: values.title.trim(), message: values.message.trim(), resumeAt });
          setData(result); setNotice(values.mode === "WHITELIST" ? "灰度白名单已开启，非名单玩家将收到更新提示。" : "全量访问已恢复。");
        } catch (reason) { setError(errorMessage(reason, "访问规则保存失败")); }
        finally { setSaving(false); }
      };
      if (values.mode !== data?.mode) modal.confirm({ title: values.mode === "WHITELIST" ? "确认限制为仅白名单访问？" : "确认恢复全部玩家访问？", content: values.mode === "WHITELIST" ? `当前只有 ${data?.whitelist.length ?? 0} 个测试账号可继续进入；其余账号和现有会话都会被拦截，但命卷不会改变。` : "所有有效账号将立即恢复进入，已保存的提示文案会保留。", okText: values.mode === "WHITELIST" ? "确认开启白名单" : "确认全部开放", cancelText: "取消", okButtonProps: { danger: values.mode === "WHITELIST" }, onOk: execute });
      else await execute();
    } catch {
      // 字段校验信息由表单原位展示。
    }
  };
  const add = async () => {
    try {
      const values = await addForm.validateFields(); setSaving(true); setError("");
      const result = await adminApi.addAccessWhitelist(values.email.trim(), values.note?.trim() || "");
      setData(result); addForm.resetFields(); setNotice("测试账号已加入白名单。");
    } catch (reason) { if (reason instanceof Error) setError(reason.message); }
    finally { setSaving(false); }
  };
  const remove = (row: AccessControl["whitelist"][number]) => modal.confirm({
    title: `移除测试账号 ${row.email}？`,
    content: data?.mode === "WHITELIST" ? "移除后该玩家会立即失去访问能力。白名单模式下不能移除最后一个账号。" : "只移除灰度身份，不修改该玩家账号或命卷。",
    okText: "确认移除", cancelText: "取消", okButtonProps: { danger: true },
    onOk: async () => { const result = await adminApi.removeAccessWhitelist(row.userId); setData(result); setNotice("测试账号已移除。"); },
  });

  const columns: TableColumnsType<AccessControl["whitelist"][number]> = [
    { title: "玩家邮箱", dataIndex: "email", width: 280, render: (value: string) => <Text strong>{value}</Text> },
    { title: "备注", dataIndex: "note", width: 320, render: (value: string) => value || <Text type="secondary">—</Text> },
    { title: "加入时间", dataIndex: "createdAt", width: 180, render: fmt },
    { title: "操作", key: "action", fixed: "right", width: 90, render: (_, row) => <Button danger disabled={data?.mode === "WHITELIST" && data.whitelist.length <= 1} onClick={() => remove(row)} size="small" type="link">移除</Button> },
  ];

  return <div className="operations-management-page">
    {contextHolder}
    <section className="operations-management-intro">
      <div><Title level={2}>灰度访问</Title><Paragraph>控制玩家是否能进入游戏，并维护测试账号白名单。访问限制不会修改、重置或删除命卷。</Paragraph></div>
      <Tag color={data?.mode === "WHITELIST" ? "warning" : "success"}>{data?.mode === "WHITELIST" ? "仅白名单" : "全部开放"}</Tag>
    </section>
    {error && <Alert closable description={error} onClose={() => setError("")} showIcon type="error" />}
    {notice && <Alert closable description={notice} onClose={() => setNotice("")} showIcon type="success" />}
    <section className="access-metrics"><Card><Statistic prefix={<LockKeyOpen size={20} />} title="当前访问模式" value={data?.mode === "WHITELIST" ? "灰度白名单" : "全部开放"} /></Card><Card><Statistic prefix={<ShieldCheck size={20} />} suffix="个" title="测试账号" value={data?.whitelist.length ?? 0} /></Card></section>
    <Card loading={loading && !data} title="访问规则">
      <Form className="access-rule-form" form={form} layout="vertical" requiredMark={false}>
        <Form.Item label="访问模式" name="mode"><Radio.Group optionType="button"><Radio.Button value="OPEN">全部开放</Radio.Button><Radio.Button value="WHITELIST">仅白名单</Radio.Button></Radio.Group></Form.Item>
        <Form.Item label="更新提示标题" name="title" rules={[{ required: true, message: "请填写提示标题" }, { max: 80, message: "不能超过 80 个字符" }]}><Input maxLength={80} /></Form.Item>
        <Form.Item label="更新提示内容" name="message" rules={[{ required: true, message: "请填写提示内容" }, { max: 220, message: "不能超过 220 个字符" }]}><Input.TextArea autoSize={{ minRows: 4, maxRows: 8 }} maxLength={220} showCount /></Form.Item>
        <div className="access-resume-fields"><Form.Item label="预计恢复日期（可不填）" name="resumeDate"><DatePicker allowClear format="YYYY-MM-DD" minDate={dayjs(shanghaiToday())} /></Form.Item><Form.Item label="预计恢复时刻" name="resumeTime"><TimePicker disabled={!resumeDate} format="HH:mm" minuteStep={5} /></Form.Item></div>
        <Alert description={mode === "WHITELIST" ? "名单内玩家可以正常游玩，其他玩家将暂时无法进入；命卷不会改变。" : "当前不限制玩家进入；提示文案会保留，供下次灰度使用。"} showIcon type={mode === "WHITELIST" ? "warning" : "info"} />
        <div className="access-form-actions"><Button loading={saving} onClick={() => void save()} type="primary">保存访问规则</Button></div>
      </Form>
    </Card>
    <Card className="operations-table-card" title="测试账号白名单">
      <Form className="access-add-form" form={addForm} layout="inline" requiredMark={false}><Form.Item name="email" rules={[{ required: true, message: "请输入玩家邮箱" }, { type: "email", message: "邮箱格式不正确" }]}><Input inputMode="email" placeholder="player@example.com" /></Form.Item><Form.Item name="note"><Input maxLength={200} placeholder="备注，例如核心玩法测试" /></Form.Item><Form.Item><Button icon={<Plus size={16} />} loading={saving} onClick={() => void add()}>加入测试账号</Button></Form.Item></Form>
      <Table columns={columns} dataSource={data?.whitelist ?? []} loading={loading} locale={{ emptyText: <Empty description="还没有测试账号；至少加入一个后才能开启白名单" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} pagination={false} rowKey="userId" scroll={{ x: 900 }} />
    </Card>
  </div>;
}
