import { Alert, Form, Input, Modal, Typography } from "antd";
import { useState } from "react";
import { adminApi, clearToken } from "../../admin-api";

const { Paragraph } = Typography;
type PasswordForm = { oldPassword: string; newPassword: string; confirmPassword: string };

/** 修改当前管理密码会让全部会话失效，因此成功后不在原页面停留，而是统一退出到登录页。 */
export function ChangePasswordModal({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const [form] = Form.useForm<PasswordForm>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async () => {
    setError("");
    try {
      const values = await form.validateFields();
      setBusy(true);
      await adminApi.changePassword(values.oldPassword, values.newPassword);
      clearToken();
      onChanged();
    } catch (reason) {
      if (reason instanceof Error) setError(reason.message);
    } finally {
      setBusy(false);
    }
  };

  return <Modal
    cancelText="取消"
    confirmLoading={busy}
    destroyOnHidden
    maskClosable={!busy}
    onCancel={busy ? undefined : onClose}
    onOk={() => void submit()}
    okText="确认修改并退出"
    open
    title="修改管理密码"
  >
    <Paragraph>修改成功后，当前管理台会自动退出，其他已登录会话也会同时失效。</Paragraph>
    <Form form={form} layout="vertical" requiredMark={false}>
      <Form.Item label="当前密码" name="oldPassword" rules={[{ required: true, message: "请输入当前密码" }]}>
        <Input.Password autoComplete="current-password" maxLength={72} />
      </Form.Item>
      <Form.Item label="新密码" name="newPassword" rules={[{ required: true, message: "请输入新密码" }, { min: 8, message: "密码不能少于 8 位" }]}>
        <Input.Password autoComplete="new-password" maxLength={72} />
      </Form.Item>
      <Form.Item dependencies={["newPassword"]} label="再次输入新密码" name="confirmPassword" rules={[
        { required: true, message: "请再次输入新密码" },
        ({ getFieldValue }) => ({ validator(_, value) { return !value || value === getFieldValue("newPassword") ? Promise.resolve() : Promise.reject(new Error("两次输入的新密码不一致")); } }),
      ]}>
        <Input.Password autoComplete="new-password" maxLength={72} />
      </Form.Item>
    </Form>
    {error && <Alert description={error} showIcon type="error" />}
  </Modal>;
}
