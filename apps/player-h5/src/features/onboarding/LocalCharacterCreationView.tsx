import { useEffect, useRef, useState } from "react";
import "./character-creator.css";
import { ArrowRightIcon, CheckIcon, ChevronDownIcon, ReloadIcon } from "@radix-ui/react-icons";
import { ProblemNotice, type UiProblem } from "../../shared/problem";
import type { CreationPath, SuggestionKind } from "../../runtime/runtime-api";

type CreationStep = "identity" | "path" | "review";
export type LocalCharacterDraft = {
  name: string; gender: "未设定" | "男" | "女"; goal: string;
  origin: string; story_preference: string; step: CreationStep;
};
export type LocalOpeningPreview = {
  opening_id: "qingmu-trial"; title: string; narrative: string[]; realm: string;
  condition: string; location: string; items: string[]; abilities: string[];
};
type Suggestions = {
  names: string[]; paths: CreationPath[]; busy: boolean; kind: SuggestionKind | null;
  pending: boolean; error: UiProblem | null; generate: (kind: SuggestionKind) => void; resume: () => void;
};
const STEPS: { id: CreationStep; label: string }[] = [
  { id: "identity", label: "姓名" }, { id: "path", label: "来历" }, { id: "review", label: "开篇" },
];
const PREFERENCES = [
  ["人物交流", "多一些人物交流，让关系自然发展。"],
  ["游历探索", "多一些游历与探索，慢慢发现线索。"],
  ["日常修行", "偏重日常生活与修行，节奏舒缓一些。"],
];
const NO_PATHS: CreationPath[] = [];

/** 卷册式创角视图：翻看候选与选用分开；只修改草稿，事实和开篇仍由原确认流程建立。 */
export function LocalCharacterCreationView({ draft, preview, isBusy, confirmed, error, onDraft,
  onCreate, onRetry, suggestions, onChoosePath }: {
  draft: LocalCharacterDraft; preview: LocalOpeningPreview | null; isBusy: boolean;
  confirmed: boolean; error: UiProblem | null; onDraft: (patch: Partial<LocalCharacterDraft>) => void;
  onCreate: () => void; onRetry: () => void; suggestions?: Suggestions; onChoosePath?: (path: CreationPath) => void;
}) {
  const { step } = draft;
  const paths = suggestions?.paths ?? NO_PATHS;
  const chosenIndex = paths.findIndex(path => draft.origin === path.origin && draft.goal === path.goal);
  const [pathIndex, setPathIndex] = useState(() => Math.max(0, chosenIndex));
  const activeIndex = Math.min(pathIndex, Math.max(0, paths.length - 1));
  const inspectedPath = paths[activeIndex];
  const [editing, setEditing] = useState(false);
  const [preferenceEditing, setPreferenceEditing] = useState(() => !suggestions || Boolean(
    draft.story_preference && !PREFERENCES.some(([, value]) => value === draft.story_preference)));
  const [nameError, setNameError] = useState(false);
  const [goalError, setGoalError] = useState(false);
  const viewRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const goalRef = useRef<HTMLTextAreaElement>(null);
  const stepNumber = STEPS.findIndex(item => item.id === step) + 1;
  const nameValid = draft.name.trim().length >= 2 && draft.name.trim().length <= 6;
  const disabled = isBusy || confirmed;
  const hasDraft = Boolean(draft.origin.trim() || draft.goal.trim());
  // 新的一批先翻到已选项或首页；翻页本身不改人物来历或目标。
  useEffect(() => { setPathIndex(Math.max(0, chosenIndex)); }, [paths]);

  function move(next: CreationStep) {
    onDraft({ step: next });
    window.requestAnimationFrame(() => {
      viewRef.current?.scrollIntoView({ block: "start" });
      headingRef.current?.focus({ preventScroll: true });
    });
  }
  function nextFromPath() {
    if (draft.goal.trim()) { setGoalError(false); move("review"); return; }
    setGoalError(true); setEditing(true);
    window.requestAnimationFrame(() => goalRef.current?.focus());
  }

  return <div className="character-creator" data-creation-step={step} ref={viewRef}>
    <aside className="creator-cover" aria-hidden="true">
      <span className="creator-cover-label">命卷</span>
      <strong className="creator-cover-name">{draft.name.trim() || "未题名"}</strong>
    </aside>
    <div className="creator-folio">
      <nav className="creator-bookmarks" aria-label={`创建命卷，第${stepNumber}步，共3步`}>
        <ol>{STEPS.map((item, index) => <li key={item.id}>
          <button type="button" aria-current={item.id === step ? "step" : undefined}
            disabled={disabled || index + 1 > stepNumber} onClick={() => move(item.id)}>{item.label}</button>
        </li>)}</ol>
      </nav>
      <header className="creator-heading">
        <h1 ref={headingRef} tabIndex={-1}>{step === "identity" ? "你的姓名" : step === "path" ? "人物来历" : "故事开篇"}</h1>
        <span aria-hidden="true" className="creator-leaf-number">{["一", "二", "三"][stepNumber - 1]}</span>
      </header>

      {step === "identity" && <div className="creator-stage">
        <section className="creator-name-section" aria-label="姓名">
          <div className="creator-name-input">
            <input ref={nameRef} aria-label="角色姓名" maxLength={6} disabled={disabled}
              aria-invalid={nameError && !nameValid} aria-describedby={nameError && !nameValid ? "creator-name-error" : undefined}
              onChange={e => onDraft({ name: e.currentTarget.value })} placeholder="写下姓名" value={draft.name} />
            {suggestions && <button className="creator-text-button" type="button"
              disabled={disabled || suggestions.busy || suggestions.pending} onClick={() => suggestions.generate("names")}>
              <ReloadIcon aria-hidden="true" />{suggestions.busy && suggestions.kind === "names" ? "正在取名…" : suggestions.names.length ? "换一批姓名" : "生成姓名"}
            </button>}
          </div>
          {nameError && !nameValid && <p className="creator-field-error" id="creator-name-error" role="alert">姓名需要 2—6 个字。</p>}
          {Boolean(suggestions?.names.length) && <div className="creator-name-options" role="group" aria-label="备选姓名">
            {suggestions!.names.map(name => <button key={name} type="button" disabled={disabled}
              aria-pressed={draft.name === name} onClick={() => onDraft({ name })}>
              <span>{name}</span>{draft.name === name && <CheckIcon aria-hidden="true" />}
            </button>)}
          </div>}
        </section>
        <fieldset className="creator-gender"><legend>性别</legend><div>
          {(["男", "女", "未设定"] as const).map(gender => <button key={gender} type="button"
            disabled={disabled} aria-pressed={draft.gender === gender} onClick={() => onDraft({ gender })}>
            {gender === "未设定" ? "不设定" : gender}
          </button>)}
        </div></fieldset>
        <footer className="creator-actions"><button className="creator-primary" aria-label="下一步 · 选择来处"
          disabled={disabled} type="button" onClick={() => {
            if (nameValid) move("path"); else { setNameError(true); nameRef.current?.focus(); }
          }}>定名<ArrowRightIcon aria-hidden="true" /></button></footer>
      </div>}

      {step === "path" && <div className="creator-stage">
        {suggestions && <section className="creator-paths" aria-label="选择来历">
          {(paths.length > 0 || hasDraft) && <div className="creator-section-heading">
            <button className="creator-text-button" type="button" aria-label={paths.length ? "换一批来历" : "生成来历与目标"}
              disabled={disabled || suggestions.busy || suggestions.pending || !preview} onClick={() => suggestions.generate("paths")}>
              <ReloadIcon aria-hidden="true" />{suggestions.busy && suggestions.kind === "paths" ? "生成中…" : paths.length ? "换一批" : "生成来历"}
            </button>
          </div>}
          {inspectedPath && <div className="creator-path-browser">
            <div className="creator-path-tabs" role="tablist" aria-label="来历候选">
              {paths.map((path, index) => <button type="button" role="tab" key={index} id={`creator-path-tab-${index}`}
                aria-label={chosenIndex === index ? `${path.title}，已选用` : path.title}
                aria-selected={activeIndex === index} aria-controls="creator-path-panel" tabIndex={activeIndex === index ? 0 : -1}
                data-chosen={chosenIndex === index} disabled={disabled} onClick={() => setPathIndex(index)}
                onKeyDown={event => {
                  const next = event.key === "ArrowRight" ? (index + 1) % paths.length
                    : event.key === "ArrowLeft" ? (index + paths.length - 1) % paths.length
                      : event.key === "Home" ? 0 : event.key === "End" ? paths.length - 1 : null;
                  if (next === null) return;
                  event.preventDefault(); setPathIndex(next);
                  event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>("button")[next]?.focus();
                }}>
                <span>{["一", "二", "三"][index]}</span><strong>{path.title}</strong>
                {chosenIndex === index && <CheckIcon aria-hidden="true" />}
              </button>)}
            </div>
            <div className="creator-path-leaf" id="creator-path-panel" role="tabpanel" aria-labelledby={`creator-path-tab-${activeIndex}`}>
              <h2>{inspectedPath.title}</h2><p>{inspectedPath.origin}</p>
              <dl><div><dt>眼下目标</dt><dd>{inspectedPath.goal}</dd></div></dl>
              <button className="creator-use-path" type="button" disabled={disabled} aria-pressed={chosenIndex === activeIndex}
                onClick={() => { onChoosePath?.(inspectedPath); setEditing(false); setGoalError(false); }}>
                {chosenIndex === activeIndex ? <><CheckIcon aria-hidden="true" />已选用此来历</> : <>选用此来历<ArrowRightIcon aria-hidden="true" /></>}
              </button>
            </div>
          </div>}
          {!paths.length && !hasDraft && <div className="creator-blank-leaf">
            <button className="creator-generate" type="button" aria-label="生成来历与目标"
              disabled={disabled || suggestions.busy || suggestions.pending || !preview} onClick={() => suggestions.generate("paths")}>
              <ReloadIcon aria-hidden="true" />{suggestions.busy && suggestions.kind === "paths" ? "正在生成来历…" : "生成三份来历"}
            </button>
          </div>}
          {hasDraft && chosenIndex < 0 && <details className="creator-selected-draft" aria-label="当前来历">
            <summary>当前来历<ChevronDownIcon aria-hidden="true" /></summary>
            {draft.origin && <p>{draft.origin}</p>}{draft.goal && <p><span>目标</span>{draft.goal}</p>}
          </details>}
        </section>}
        <details className="creator-editor" open={editing || !suggestions} onToggle={event => setEditing(event.currentTarget.open)}>
          <summary>{hasDraft ? "修改来历与目标" : "自己填写"}<ChevronDownIcon aria-hidden="true" /></summary>
          <div className="creator-fields">
            <label><span>人物来处</span><textarea aria-label="人物来处" maxLength={240} rows={3} disabled={disabled}
              onChange={e => onDraft({ origin: e.currentTarget.value })} placeholder="出身、经历……" value={draft.origin} /></label>
            <label><span>当前目标</span><textarea ref={goalRef} aria-label="当前目标" maxLength={240} rows={2} disabled={disabled}
              aria-invalid={goalError && !draft.goal.trim()} aria-describedby={goalError && !draft.goal.trim() ? "creator-goal-error" : undefined}
              onChange={e => onDraft({ goal: e.currentTarget.value })} placeholder="眼下最想做什么？" value={draft.goal} /></label>
            {goalError && !draft.goal.trim() && <p className="creator-field-error" id="creator-goal-error" role="alert">写下眼下想做的事，或选择一份来历。</p>}
          </div>
        </details>
        <fieldset className="creator-preferences"><legend>故事偏好</legend>
          <div className="creator-preference-options">{PREFERENCES.map(([label, value]) => <button key={label} type="button"
            disabled={disabled} aria-pressed={draft.story_preference === value}
            onClick={() => onDraft({ story_preference: draft.story_preference === value ? "" : value })}>{label}</button>)}</div>
          <details className="creator-custom-preference" open={preferenceEditing} onToggle={event => setPreferenceEditing(event.currentTarget.open)}>
            <summary>补充偏好<ChevronDownIcon aria-hidden="true" /></summary><label><textarea aria-label="叙事偏好"
              maxLength={240} rows={2} disabled={disabled} onChange={e => onDraft({ story_preference: e.currentTarget.value })}
              placeholder="想多看些什么，或避开什么？" value={draft.story_preference} /></label>
          </details>
        </fieldset>
        <footer className="creator-actions">
          <button className="creator-secondary" type="button" aria-label="返回修改身份" disabled={disabled} onClick={() => move("identity")}>返回</button>
          <button className="creator-primary" type="button" aria-label="下一步 · 核对命卷" disabled={disabled || Boolean(paths.length && !draft.goal.trim())}
            onClick={nextFromPath}>下一页<ArrowRightIcon aria-hidden="true" /></button>
        </footer>
      </div>}

      {step === "review" && <div className="creator-stage">
        <section className="creator-review-character" aria-labelledby="local-creation-summary">
          <div className="creator-review-name"><h2 id="local-creation-summary">{draft.name}</h2>{draft.gender !== "未设定" && <span>{draft.gender}</span>}</div>
          <dl><div><dt>目标</dt><dd>{draft.goal}</dd></div>{draft.origin && <div><dt>来历</dt><dd>{draft.origin}</dd></div>}
            {draft.story_preference && <div><dt>偏好</dt><dd>{draft.story_preference}</dd></div>}</dl>
        </section>
        {preview && <section className="creator-opening" aria-label="开篇预览">
          <h2>{preview.title}</h2>{preview.narrative[0] && <p>{preview.narrative[0]}</p>}
          {preview.narrative.length > 1 && <details><summary>展开开篇<ChevronDownIcon aria-hidden="true" /></summary>
            {preview.narrative.slice(1).map((line, i) => <p key={i}>{line}</p>)}
          </details>}
          <dl><div><dt>境界</dt><dd>{preview.realm} · {preview.condition}</dd></div><div><dt>所在</dt><dd>{preview.location}</dd></div>
            {preview.items.length > 0 && <div><dt>随身</dt><dd>{preview.items.join("、")}</dd></div>}
            {preview.abilities.length > 0 && <div><dt>所学</dt><dd>{preview.abilities.join("、")}</dd></div>}</dl>
        </section>}
        <footer className="creator-actions">
          <button className="creator-secondary" type="button" aria-label="返回修改来处与偏好" disabled={disabled} onClick={() => move("path")}>返回</button>
          <button className="creator-primary" type="button" aria-label={isBusy ? "命卷正在落笔…" : confirmed ? "找回已确认命卷" : "确认并开启命卷"}
            disabled={isBusy || (!preview && !confirmed) || !nameValid || !draft.goal.trim()} onClick={onCreate}>
            {isBusy ? "正在开篇…" : confirmed ? "找回命卷" : "开启命卷"}<ArrowRightIcon aria-hidden="true" />
          </button>
        </footer>
      </div>}
      {suggestions?.pending && !suggestions.busy && <button className="creator-text-button creator-resume" type="button"
        disabled={disabled} onClick={() => suggestions.resume()}>继续生成候选</button>}
      {suggestions?.busy && <p className="creator-progress" role="status">{suggestions.kind === "names" ? "正在生成姓名候选…" : "正在生成来历候选…"}</p>}
      {suggestions?.error && <ProblemNotice compact problem={suggestions.error} />}
      {error && <ProblemNotice compact problem={error} />}
      {!preview && <button className="creator-text-button creator-resume" type="button" disabled={isBusy} onClick={onRetry}>重新读取开篇</button>}
    </div>
  </div>;
}
