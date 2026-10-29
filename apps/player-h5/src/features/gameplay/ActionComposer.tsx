import { useEffect, useRef } from "react";
import { ChevronDownIcon, Cross2Icon, EnterIcon, Pencil1Icon } from "@radix-ui/react-icons";
import { revealFormControl } from "../../app/local-runtime";

/** 原自由行动输入区；两种数据入口共用布局、草稿控件和原生键盘行为，不在此提交或裁决行动。 */
export function ActionComposer({ open, draft, locked, offline = false, maxLength, placeholder, onToggle, onDraft, onFocusChange, onSubmit }: {
  open: boolean;
  draft: string;
  locked: boolean;
  offline?: boolean;
  maxLength: number;
  placeholder: string;
  onToggle: () => void;
  onDraft: (value: string) => void;
  onFocusChange: (focused: boolean) => void;
  onSubmit: (action: string) => void;
}) {
  const blurTimer = useRef<ReturnType<typeof window.setTimeout> | null>(null);
  const clearBlurTimer = () => {
    if (blurTimer.current !== null) window.clearTimeout(blurTimer.current);
    blurTimer.current = null;
  };
  useEffect(() => clearBlurTimer, []);

  return <section aria-labelledby="turn-action-title" className="turn-action-zone" data-testid="turn-action-zone">
    <button disabled={locked} aria-expanded={open} aria-label="展开自定义行动，不采用推荐选项时，亲自描述下一步" className="turn-action-toggle" onClick={onToggle} type="button"><span className="turn-action-icon"><Pencil1Icon /></span><strong id="turn-action-title">或，写下自己的行动</strong><ChevronDownIcon aria-hidden="true" /></button>
    {open && <form className="action-composer" data-has-draft={Boolean(draft.trim())} onSubmit={event => {
      event.preventDefault();
      if (locked || offline || !draft.trim()) return;
      event.currentTarget.querySelector("textarea")?.blur();
      clearBlurTimer();
      onFocusChange(false);
      onSubmit(draft.trim());
    }}><div className="action-composer-input"><textarea aria-label="自由行动" readOnly={locked} maxLength={maxLength} value={draft} onChange={event => onDraft(event.currentTarget.value)} onFocus={event => {
      clearBlurTimer();
      onFocusChange(true);
      revealFormControl(event.currentTarget, 160);
    }} onBlur={() => {
      clearBlurTimer();
      blurTimer.current = window.setTimeout(() => onFocusChange(false), 120);
    }} rows={4} placeholder={placeholder} />{draft && !locked && <button aria-label="清空输入" className="clear-draft" onClick={() => onDraft("")} type="button"><Cross2Icon /></button>}</div><div className="action-composer-footer"><span className="action-composer-meta">{locked ? "确认原行动结果后才能提交新行动" : "草稿会在切换选项后保留"}</span><button className="act-button" disabled={locked || offline || !draft.trim()} aria-label="确认此行动" type="submit"><span>确认行动</span><EnterIcon /></button></div></form>}
  </section>;
}
