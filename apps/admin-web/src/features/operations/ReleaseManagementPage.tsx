import "./OperationsManagementPages.css";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert, Button, Card, Drawer, Empty, Form, Input, Modal, Pagination, Select, Space,
  Switch, Table, Tag, Typography, type TableColumnsType,
} from "antd";
import { Megaphone, Plus } from "@phosphor-icons/react";
import { adminApi, type ReleaseNoteAdmin } from "../../admin-api";

const { Paragraph, Text, Title } = Typography;
type ReleaseForm = Pick<ReleaseNoteAdmin, "version" | "title" | "content" | "announceAll" | "showOnLogin">;
const STATUS_META: Record<ReleaseNoteAdmin["status"], { label: string; color: string }> = {
  DRAFT: { label: "草稿", color: "default" }, PUBLISHED: { label: "已发布", color: "success" }, ARCHIVED: { label: "已归档", color: "warning" },
};
const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";
const errorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;

/** 版本公告管理保留完整历史；发布版本、全员公告和登录页展示是三个独立概念，界面不再混成一个开关。 */
export function ReleaseManagementPage({ currentVersion, onVersionChanged }: { currentVersion: string; onVersionChanged: () => void }) {
  const [notes, setNotes] = useState<ReleaseNoteAdmin[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [versionDraft, setVersionDraft] = useState(currentVersion);
  const [editing, setEditing] = useState<Partial<ReleaseNoteAdmin> | null>(null);
  const [modal, contextHolder] = Modal.useModal();

  useEffect(() => setVersionDraft(currentVersion), [currentVersion]);
  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setNotes(await adminApi.releaseNotes()); }
    catch (reason) { setError(errorMessage(reason, "版本公告加载失败")); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const pinned = notes.find((note) => note.status === "PUBLISHED" && note.showOnLogin);
  const filtered = useMemo(() => notes.filter((note) => {
    const matchesStatus = !status || note.status === status;
    const keyword = search.trim().toLowerCase();
    return matchesStatus && (!keyword || `${note.version} ${note.title} ${note.content}`.toLowerCase().includes(keyword));
  }), [notes, search, status]);
  const visible = filtered.slice((page - 1) * 10, page * 10);
  useEffect(() => setPage(1), [search, status]);

  const updateVersion = () => modal.confirm({
    title: `将当前游戏版本调整为 ${versionDraft.trim().toUpperCase()}？`,
    content: "H5、世界页和管理台会统一读取新版本标识。请只在对应版本已经发布后执行。",
    okText: "确认更新", cancelText: "取消",
    onOk: async () => { const result = await adminApi.updateAppVersion(versionDraft.trim()); setVersionDraft(result.version); setNotice(`当前游戏版本已调整为 ${result.version}。`); onVersionChanged(); },
  });
  const clearPinned = () => pinned && modal.confirm({
    title: "停止登录页展示当前公告？",
    content: `${pinned.version} · ${pinned.title} 将不再出现在登录页；公告历史和已读记录不会删除。`,
    okText: "停止展示", cancelText: "取消", okButtonProps: { danger: true },
    onOk: async () => { const result = await adminApi.clearLoginAnnouncement(); setNotice(result.message); await load(); },
  });

  const columns: TableColumnsType<ReleaseNoteAdmin> = [
    { title: "版本", dataIndex: "version", width: 110, render: (value: string) => <Text strong>{value}</Text> },
    { title: "公告", key: "note", width: 420, render: (_, row) => <div className="release-note-cell"><strong>{row.title}</strong><span>{row.content}</span></div> },
    { title: "状态", dataIndex: "status", width: 110, render: (value: ReleaseNoteAdmin["status"]) => <Tag color={STATUS_META[value].color}>{STATUS_META[value].label}</Tag> },
    { title: "触达", key: "delivery", width: 180, render: (_, row) => row.showOnLogin ? <Tag color="blue">登录页展示</Tag> : row.announceAll ? <span>全员公告 · {row.readCount} 人已读</span> : <Text type="secondary">仅版本记录</Text> },
    { title: "更新时间", dataIndex: "updatedAt", width: 180, render: fmt },
    { title: "操作", key: "action", fixed: "right", width: 88, render: (_, row) => <Button onClick={() => setEditing(row)} size="small" type="link">编辑</Button> },
  ];

  return <div className="operations-management-page">
    {contextHolder}
    <section className="operations-management-intro">
      <div><Title level={2}>版本公告</Title><Paragraph>管理当前版本、登录页公告和历史公告。</Paragraph></div>
      <Button disabled={!currentVersion} icon={<Plus size={17} />} onClick={() => setEditing({ version: currentVersion, title: "", content: "", status: "DRAFT", announceAll: false, showOnLogin: false })} type="primary">新建公告</Button>
    </section>
    {error && <Alert action={<Button onClick={() => void load()} size="small">重试</Button>} description={error} showIcon type="error" />}
    {notice && <Alert closable description={notice} onClose={() => setNotice("")} showIcon type="success" />}
    <div className="release-control-grid">
      <Card title="当前游戏版本"><div className="release-version-control"><strong>{currentVersion || "正在同步"}</strong><Paragraph>发版完成后再调整，避免线上页面提前展示尚未发布的版本。</Paragraph><Space.Compact block><Input aria-label="当前游戏版本" maxLength={16} onChange={(event) => setVersionDraft(event.currentTarget.value)} placeholder="V0.16" value={versionDraft} /><Button disabled={!versionDraft.trim() || versionDraft.trim().toUpperCase() === currentVersion} onClick={updateVersion}>更新版本</Button></Space.Compact></div></Card>
      <Card title="当前登录页公告"><div className="release-pinned-status">{pinned ? <><Megaphone size={30} weight="duotone" /><div><Text strong>{pinned.version} · {pinned.title}</Text><Text type="secondary">发布新公告不会自动替换当前展示内容。</Text></div><Button danger onClick={clearPinned}>停止展示</Button></> : <Empty description="登录页当前没有固定公告" image={Empty.PRESENTED_IMAGE_SIMPLE} />}</div></Card>
    </div>
    <Card className="operations-table-card" variant="borderless">
      <div className="operations-table-heading"><div><Text strong>全部公告历史</Text><Text type="secondary">共 {filtered.length} 条</Text></div><Space wrap><Input.Search allowClear onChange={(event) => setSearch(event.currentTarget.value)} placeholder="搜索版本、标题或内容" value={search} /><Select onChange={setStatus} options={[{ value: "", label: "全部状态" }, ...Object.entries(STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))]} value={status} /></Space></div>
      <Table<ReleaseNoteAdmin> columns={columns} dataSource={visible} loading={loading} locale={{ emptyText: <Empty description="没有符合条件的版本公告" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }} pagination={false} rowKey="id" scroll={{ x: 1100 }} />
      <Pagination current={page} hideOnSinglePage onChange={setPage} pageSize={10} showSizeChanger={false} total={filtered.length} />
    </Card>
    <ReleaseEditorDrawer note={editing} onClose={() => setEditing(null)} onSaved={async (message) => { setEditing(null); setNotice(message); await load(); }} />
  </div>;
}

function ReleaseEditorDrawer({ note, onClose, onSaved }: { note: Partial<ReleaseNoteAdmin> | null; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  const [form] = Form.useForm<ReleaseForm>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [modal, contextHolder] = Modal.useModal();
  const announceAll = Form.useWatch("announceAll", form);
  useEffect(() => {
    if (!note) return;
    form.setFieldsValue({ version: note.version || "", title: note.title || "", content: note.content || "", announceAll: Boolean(note.announceAll), showOnLogin: Boolean(note.showOnLogin) });
    setError("");
  }, [form, note]);
  useEffect(() => { if (announceAll === false) form.setFieldValue("showOnLogin", false); }, [announceAll, form]);

  const save = async (status: ReleaseNoteAdmin["status"]) => {
    setError("");
    try {
      const values = await form.validateFields();
      const execute = async () => {
        setBusy(true);
        try { await adminApi.saveReleaseNote({ ...note, ...values, status }); await onSaved(status === "PUBLISHED" ? "版本公告已发布。" : status === "ARCHIVED" ? "版本公告已归档并撤下。" : "公告草稿已保存。"); }
        catch (reason) { setError(errorMessage(reason, "公告保存失败")); }
        finally { setBusy(false); }
      };
      if (status === "DRAFT") return void execute();
      modal.confirm({ title: status === "ARCHIVED" ? "归档并撤下这条公告？" : values.announceAll ? "发布并向全部玩家公告？" : "发布为版本记录？", content: status === "ARCHIVED" ? "登录页展示会同时撤下，公告历史不会删除。" : values.showOnLogin ? "本公告将成为唯一的登录页固定公告，并替换原展示位。" : values.announceAll ? "玩家登录后会收到一次公告；现有登录页公告不会被替换。" : "只记录版本内容，不会主动打扰玩家。", okText: status === "ARCHIVED" ? "确认归档" : "确认发布", cancelText: "取消", okButtonProps: { danger: status === "ARCHIVED" }, onOk: execute });
    } catch {
      // 表单会在对应字段下显示校验原因。
    }
  };

  return <>{contextHolder}<Drawer className="release-editor-drawer" destroyOnHidden extra={<Tag color={note?.status ? STATUS_META[note.status].color : "default"}>{note?.status ? STATUS_META[note.status].label : "新公告"}</Tag>} onClose={onClose} open={Boolean(note)} size={640} title={note?.id ? "编辑版本公告" : "新建版本公告"}>
    <Form form={form} layout="vertical" requiredMark={false}>
      <Form.Item label="版本" name="version" rules={[{ required: true, message: "请填写版本号" }]}><Input maxLength={24} placeholder="V0.16" /></Form.Item>
      <Form.Item label="标题" name="title" rules={[{ required: true, message: "请填写公告标题" }]}><Input maxLength={120} /></Form.Item>
      <Form.Item label="更新内容" name="content" rules={[{ required: true, message: "请填写公告内容" }]}><Input.TextArea autoSize={{ minRows: 8, maxRows: 16 }} maxLength={4000} showCount /></Form.Item>
      <Form.Item label="向全部玩家公告一次" name="announceAll" valuePropName="checked"><Switch checkedChildren="开启" unCheckedChildren="关闭" /></Form.Item>
      <Form.Item extra="登录页同时只能展示一条；发布本公告后会替换原展示位。" label="固定展示在登录页" name="showOnLogin" valuePropName="checked"><Switch checkedChildren="展示" disabled={!announceAll} unCheckedChildren="不展示" /></Form.Item>
    </Form>
    {error && <Alert description={error} showIcon type="error" />}
    <div className="release-editor-actions"><Button disabled={busy} onClick={() => void save("DRAFT")}>保存草稿</Button>{note?.id && <Button danger disabled={busy} onClick={() => void save("ARCHIVED")}>归档并撤下</Button>}<Button loading={busy} onClick={() => void save("PUBLISHED")} type="primary">{announceAll ? "发布并全员公告" : "发布版本记录"}</Button></div>
  </Drawer></>;
}
