import { useEffect, useState } from "react";
import { ChevronRightIcon, EyeClosedIcon, EyeOpenIcon } from "@radix-ui/react-icons";
import { Button, Dialog, IconButton, Tabs, TextField, Theme } from "@radix-ui/themes";
import { useForm } from "react-hook-form";
import type { AccessStatus, ReleaseNote } from "../../game-api";
import { ProblemNotice, type UiProblem } from "../../shared/problem";

export type MaintenanceNotice = Pick<AccessStatus, "title" | "message" | "resumeAt">;

type AuthFormValues = {
  email: string;
  password: string;
  confirmation: string;
};

export function LoginView({
  isBusy,
  error,
  offline,
  announcement,
  accessStatus,
  onAuthenticate,
}: {
  isBusy: boolean;
  error: UiProblem | null;
  offline: boolean;
  announcement: ReleaseNote | null;
  accessStatus: AccessStatus | null;
  onAuthenticate: (mode: "login" | "register", email: string, password: string) => void;
}) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmation, setShowConfirmation] = useState(false);
  const {
    clearErrors,
    formState: { errors },
    getValues,
    handleSubmit,
    register,
    resetField,
    watch,
  } = useForm<AuthFormValues>({
    defaultValues: { email: "", password: "", confirmation: "" },
    mode: "onSubmit",
    reValidateMode: "onChange",
    shouldFocusError: true,
    shouldUnregister: false,
  });
  const email = watch("email") ?? "";
  const password = watch("password") ?? "";
  const confirmation = watch("confirmation") ?? "";
  const mismatch = mode === "register" && confirmation.length > 0 && confirmation !== password;

  const selectMode = (nextMode: "login" | "register") => {
    if (nextMode === mode) return;
    setMode(nextMode);
    resetField("password");
    resetField("confirmation");
    clearErrors();
    setShowPassword(false);
    setShowConfirmation(false);
  };

  useEffect(() => {
    if (accessStatus?.restricted && mode !== "login") selectMode("login");
  }, [accessStatus?.restricted, accessStatus?.title, accessStatus?.message, accessStatus?.resumeAt]);

  return (
    <Theme appearance="light" accentColor="teal" className="login-radix-theme" grayColor="sage" panelBackground="solid" radius="small" scaling="100%">
      <section className="login-experience">
      <div className="login-hero">
        <div className="login-brand-copy">
          <div aria-label="问仙录" className="login-brand-mark" role="img" />
        </div>
      </div>
      <div className="login-panel">
        <Tabs.Root className="login-tabs" onValueChange={(value) => selectMode(value as "login" | "register")} value={mode}>
          <Tabs.List aria-label="账号入口" className="login-mode-switch">
            <Tabs.Trigger disabled={isBusy} value="login">登录</Tabs.Trigger>
            <Tabs.Trigger disabled={isBusy || Boolean(accessStatus?.restricted)} value="register">注册</Tabs.Trigger>
          </Tabs.List>
        </Tabs.Root>
        {accessStatus?.restricted && <aside className="registration-status-note" role="status">
          <strong>当前暂停新账号注册，已有账号可正常登录。</strong>
          <span>{accessStatus.message || "已有账号仍可正常登录。"}</span>
          {accessStatus.resumeAt && <small>预计恢复：{new Date(accessStatus.resumeAt).toLocaleString("zh-CN")}</small>}
        </aside>}
        <form
          className="entry-form"
          noValidate
          onSubmit={handleSubmit((values) => onAuthenticate(mode, values.email.trim(), values.password))}
        >
          <div className="entry-field">
            <label htmlFor="auth-email">邮箱</label>
            <TextField.Root
              aria-describedby={errors.email ? "auth-email-error" : undefined}
              aria-invalid={Boolean(errors.email) || undefined}
              disabled={isBusy}
              id="auth-email"
              autoComplete="email"
              inputMode="email"
              className="login-text-field"
              placeholder="请输入邮箱"
              type="email"
              {...register("email", {
                required: "请输入邮箱。",
                maxLength: { value: 254, message: "邮箱地址过长，请检查后重试。" },
                pattern: { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: "请输入完整的邮箱地址。" },
              })}
            />
            {errors.email && <small className="field-error" id="auth-email-error" role="alert">{errors.email.message}</small>}
          </div>
          <div className="entry-field">
            <div className="entry-field-label">
              <label htmlFor="auth-password">密码</label>
              {mode === "register" && <small id="auth-password-requirement">8 至 72 个字符</small>}
            </div>
            <TextField.Root
              aria-describedby={[mode === "register" ? "auth-password-requirement" : "", errors.password ? "auth-password-error" : ""].filter(Boolean).join(" ") || undefined}
              aria-invalid={Boolean(errors.password) || undefined}
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              className="login-text-field"
              disabled={isBusy}
              id="auth-password"
              maxLength={mode === "register" ? 72 : 255}
              placeholder="请输入密码"
              type={showPassword ? "text" : "password"}
              {...register("password", {
                required: "请输入密码。",
                minLength: mode === "register" ? { value: 8, message: "密码至少需要 8 个字符。" } : undefined,
                maxLength: mode === "register"
                  ? { value: 72, message: "密码最多为 72 个字符。" }
                  : { value: 255, message: "密码长度超过限制。" },
              })}
            >
              <TextField.Slot side="right">
                <IconButton aria-label={showPassword ? "隐藏密码" : "显示密码"} aria-pressed={showPassword} className="password-visibility-button" disabled={isBusy} onClick={() => setShowPassword((visible) => !visible)} size="2" type="button" variant="ghost">
                  {showPassword ? <EyeClosedIcon /> : <EyeOpenIcon />}
                </IconButton>
              </TextField.Slot>
            </TextField.Root>
            {errors.password && <small className="field-error" id="auth-password-error" role="alert">{errors.password.message}</small>}
          </div>
          {mode === "register" && <div className="entry-field">
            <label htmlFor="auth-confirmation">确认密码</label>
            <TextField.Root
              aria-describedby={errors.confirmation || mismatch ? "auth-confirmation-error" : undefined}
              aria-invalid={Boolean(errors.confirmation) || mismatch || undefined}
              autoComplete="new-password"
              className="login-text-field"
              disabled={isBusy}
              id="auth-confirmation"
              maxLength={72}
              placeholder="再次输入密码"
              type={showConfirmation ? "text" : "password"}
              {...register("confirmation", {
                required: "请再次输入密码。",
                validate: (value) => value === getValues("password") || "两次输入的密码不一致，请重新确认。",
              })}
            >
              <TextField.Slot side="right">
                <IconButton aria-label={showConfirmation ? "隐藏确认密码" : "显示确认密码"} aria-pressed={showConfirmation} className="password-visibility-button" disabled={isBusy} onClick={() => setShowConfirmation((visible) => !visible)} size="2" type="button" variant="ghost">
                  {showConfirmation ? <EyeClosedIcon /> : <EyeOpenIcon />}
                </IconButton>
              </TextField.Slot>
            </TextField.Root>
            {(errors.confirmation || mismatch) && <small className="field-error" id="auth-confirmation-error" role="alert">{errors.confirmation?.message ?? "两次输入的密码不一致，请重新确认。"}</small>}
          </div>}
          <Button aria-busy={isBusy} className="primary-entry-button" data-busy={isBusy || undefined} disabled={isBusy || !email.trim() || (mode === "register" ? password.length < 8 || confirmation !== password : !password)} loading={isBusy} type="submit">
            <span>{isBusy ? "正在验明身份…" : mode === "login" ? "进入问仙录" : "注册并创建命卷"}</span>
            {!isBusy && <ChevronRightIcon aria-hidden="true" />}
          </Button>
        </form>
        {error && <ProblemNotice compact problem={error} />}
        {!error && offline && <p className="entry-error">命卷暂时无法开启，请稍后重试。</p>}
        {announcement && <details className="entry-announcement entry-announcement-fold login-announcement">
          <summary>
            <span>{announcement.version}</span>
            <strong>{announcement.title}</strong>
            <small className="fold-open">展开</small>
            <small className="fold-close">收起</small>
          </summary>
          <p>{announcement.content}</p>
        </details>}
        <p className="entry-footnote">当前不发送验证码，也暂不支持找回密码。请使用长期可用的邮箱并妥善保存密码。</p>
      </div>
      </section>
    </Theme>
  );
}

export function MaintenanceDialog({ notice, onClose, closeLabel = "知道了" }: { notice: MaintenanceNotice; onClose: () => void; closeLabel?: string }) {
  return <Theme appearance="light" accentColor="teal" className="login-dialog-theme" grayColor="sage" panelBackground="solid" radius="small" scaling="100%">
    <Dialog.Root onOpenChange={(open) => { if (!open) onClose(); }} open>
      <Dialog.Content className="ink-dialog quota-dialog login-maintenance-dialog" maxWidth="420px">
        <div className="ink-dialog-seal" aria-hidden="true">候</div><p className="eyebrow">世界正在更新</p>
        <Dialog.Title>{notice.title}</Dialog.Title>
        <Dialog.Description>{notice.message}</Dialog.Description>
        {notice.resumeAt && <div className="ink-dialog-note"><span>预计恢复</span><strong>{new Date(notice.resumeAt).toLocaleString("zh-CN")}</strong></div>}
        <div className="ink-dialog-actions single"><Dialog.Close><Button autoFocus className="ink-dialog-confirm" type="button">{closeLabel}</Button></Dialog.Close></div>
      </Dialog.Content>
    </Dialog.Root>
  </Theme>;
}
