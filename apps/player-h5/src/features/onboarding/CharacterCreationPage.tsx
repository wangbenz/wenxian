import { useEffect, useRef, useState, type FormEvent } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { ArrowLeftIcon, ArrowRightIcon, CheckIcon, ChevronDownIcon, Cross2Icon, ReloadIcon } from "@radix-ui/react-icons";
import "./character-creation-page.css";

const STORAGE_KEY = "wenxian.character-creation.frontend-draft";
const STEPS = ["人物", "来历", "求道"] as const;
const GENDERS = ["男", "女", "未设定"] as const;
type Gender = typeof GENDERS[number];
type Step = 0 | 1 | 2;
type Option = { id: string; name: string; description: string };
type ChoiceField = "origin" | "specialty" | "motivation";

// 前端展示资料。随机取名与角色选项尚未接入模型、审核或权威游戏状态。
const NAMES: Record<Gender, readonly string[]> = {
  男: ["陆青山", "温行舟", "谢怀川", "程见山", "沈砚川", "顾明修"],
  女: ["顾云萝", "沈知微", "江疏桐", "柳映竹", "陆清婉", "叶晚棠"],
  未设定: ["林照溪", "叶听澜", "许长宁", "宋清和", "江望舒", "沈知秋"],
};
const ROOTS = ["随缘而定", "金灵根", "木灵根", "水灵根", "火灵根", "土灵根"];
const ORIGINS: readonly Option[] = [
  { id: "herbal-apprentice", name: "药铺学徒", description: "在镇上药铺帮工，常替人送药。" },
  { id: "village", name: "乡野出身", description: "在山村长大，靠耕种与采集度日。" },
  { id: "escort", name: "镖行随从", description: "随商队走过几条商道，做过护送的杂活。" },
  { id: "market", name: "坊市跑腿", description: "替摊主传话送货，见过坊市里的往来。" },
];
const SPECIALTIES: readonly Option[] = [
  { id: "herbs", name: "识药", description: "辨认常见草药。" },
  { id: "sword", name: "基础剑术", description: "会使基本剑招。" },
  { id: "trail", name: "山野行走", description: "辨路、寻水，在野外找落脚处。" },
  { id: "bargain", name: "交涉", description: "与人议价，打听消息。" },
];
const MOTIVATIONS: readonly Option[] = [
  { id: "long-life", name: "求长生", description: "不愿一生止于百年。" },
  { id: "family", name: "寻找亲人", description: "沿着修行者的踪迹，寻找失散的亲人。" },
  { id: "shelter", name: "求自保", description: "想有保护自己与在意之人的本事。" },
  { id: "journey", name: "见天地", description: "想走出故乡，看看更大的世界。" },
];
const GOALS: Record<string, readonly string[]> = {
  "long-life": ["寻找师承", "寻一门修行功法", "谋份生计"],
  family: ["打听亲人的消息", "寻找知情的修士", "查探旧线索"],
  shelter: ["找一处安稳落脚的地方", "寻求修行指点", "谋份生计"],
  journey: ["结识当地修士", "打听附近的去处", "寻找同行之人"],
};

// 创角方案中的叙事用途。这里只帮助玩家比较选择，不裁决成功、授予装备或生成已发生事件。
const BACKGROUNDS: Record<string, { familiar: string; contacts: string; bonds: readonly string[] }> = {
  "herbal-apprentice": {
    familiar: "药铺日常、药材交易与送药门路。", contacts: "过去带你做事的师傅，以及常来药铺的药商。",
    bonds: ["想报答药铺师傅多年的照拂。", "惦记着一位久未露面的旧主顾。"],
  },
  village: {
    familiar: "山林路径、农事时令与乡间生活。", contacts: "村中长辈，以及一同上山的伙伴。",
    bonds: ["想让留在故乡的家人过得安稳。", "答应一位旧友，学成之后回乡相见。"],
  },
  escort: {
    familiar: "商道规矩、车队分工与沿途的运输标记。", contacts: "带过你的镖师，以及走过同一条路的同行。",
    bonds: ["有位失散的同行，至今下落不明。", "想兑现对一位老镖师的承诺。"],
  },
  market: {
    familiar: "摊贩往来、传话门路与常来坊市的面孔。", contacts: "几位熟悉的摊主，以及一起跑腿的伙伴。",
    bonds: ["一直记着一位摊主在困顿时的接济。", "想替一位旧友寻到可靠的生计。"],
  },
};
const TRAINING: Record<string, { use: string; scope: string }> = {
  herbs: { use: "遇到药材争议，可以尝试辨认常见草药、查看采收与保存的痕迹。", scope: "识药入门，炼丹与医治重症仍需另外学习。" },
  sword: { use: "使用剑类兵器时，知道基本架势与攻守方法。", scope: "基础剑术，不代表持有兵器或掌握修行剑诀。" },
  trail: { use: "野外行路时，可以尝试辨路、寻水、观察足迹与寻找落脚处。", scope: "熟悉常见野外情形，陌生秘境仍需探索。" },
  bargain: { use: "遇到谈价、求助与打听消息，可以尝试寻找门路、与人周旋。", scope: "交情与信誉仍需建立，对方可以拒绝。" },
};
const PURSUITS: Record<string, string> = {
  "long-life": "师承、修行功法与延寿线索。",
  family: "亲人的过往行踪，以及可能知情的人。",
  shelter: "安稳的落脚处、眼前的威胁与自保之术。",
  journey: "陌生地方、新结识的人与不同的修行见闻。",
};

type Draft = {
  name: string; gender: Gender; root: string;
  origin: string; originText: string;
  specialty: string; specialtyText: string;
  motivation: string; motivationText: string;
  goal: string; age: string; appearance: string; personality: string; bond: string;
};
type Errors = Partial<Record<"name" | ChoiceField | "goal", string>>;
type StoredDraft = { version: 1; draft: Draft; step: Step; visited: Step };
const emptyDraft = (): Draft => ({
  name: "", gender: "未设定", root: "随缘而定", origin: "", originText: "",
  specialty: "", specialtyText: "", motivation: "", motivationText: "", goal: "",
  age: "", appearance: "", personality: "", bond: "",
});

function selectedValue(draft: Draft, field: ChoiceField, options: readonly Option[]) {
  return draft[field] === "custom" ? draft[`${field}Text`].trim() : options.find(option => option.id === draft[field])?.name ?? "";
}

/** 独立前端草稿恢复；只读取自己的键，不认领玩家命卷，也不恢复不明格式的数据。 */
function readDraft(): StoredDraft {
  const fallback: StoredDraft = { version: 1, draft: emptyDraft(), step: 0, visited: 0 };
  try {
    const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
    if (!stored || stored.version !== 1 || !stored.draft) return fallback;
    const value = stored.draft as Record<string, unknown>;
    const draft = emptyDraft();
    for (const field of Object.keys(draft) as (keyof Draft)[]) {
      if (typeof value[field] !== "string" || (value[field] as string).length > 240) return fallback;
      Object.assign(draft, { [field]: value[field] });
    }
    if (!GENDERS.includes(draft.gender) || draft.name.length > 6 || draft.root.length > 16) return fallback;
    for (const [field, options] of [["origin", ORIGINS], ["specialty", SPECIALTIES], ["motivation", MOTIVATIONS]] as const) {
      if (draft[field] && draft[field] !== "custom" && !options.some(option => option.id === draft[field])) return fallback;
    }
    const step: Step = stored.step === 1 || stored.step === 2 ? stored.step : 0;
    const visited: Step = stored.visited === 1 || stored.visited === 2 ? stored.visited : 0;
    return { version: 1, draft, step, visited: Math.max(step, visited) as Step };
  } catch {
    return fallback;
  }
}

function ChoiceList({ field, title, options, draft, error, onChange, compact = false, summaryOnly = false }: {
  field: ChoiceField; title: string; options: readonly Option[]; draft: Draft; error?: string;
  onChange: (patch: Partial<Draft>) => void; compact?: boolean; summaryOnly?: boolean;
}) {
  return <fieldset className="cc-field" aria-describedby={error ? `${field}-error` : undefined}>
    <legend>{title}</legend>
    <div className={`cc-options${compact ? " cc-options-compact" : ""}${summaryOnly ? " cc-options-summary" : ""}`}>
      {options.map(option => <label className="cc-option" key={option.id} data-selected={draft[field] === option.id}>
        <input type="radio" name={field} value={option.id} checked={draft[field] === option.id} onChange={() => onChange({ [field]: option.id })} />
        <span className="cc-option-copy"><strong>{option.name}</strong>{!summaryOnly && <span>{option.description}</span>}</span>
        <span className="cc-choice-mark" aria-hidden="true">{draft[field] === option.id && <CheckIcon />}</span>
      </label>)}
      <label className="cc-option cc-custom-option" data-selected={draft[field] === "custom"}>
        <input type="radio" name={field} value="custom" checked={draft[field] === "custom"} onChange={() => onChange({ [field]: "custom" })} />
        <span className="cc-option-copy"><strong>自定{title}</strong></span>
        <span className="cc-choice-mark" aria-hidden="true">{draft[field] === "custom" && <CheckIcon />}</span>
      </label>
    </div>
    {draft[field] === "custom" && <textarea className="cc-textarea" aria-label={`自定${title}`} rows={3} maxLength={240} value={draft[`${field}Text`]}
      onChange={event => onChange({ [`${field}Text`]: event.target.value })} />}
    {error && <p className="cc-error" id={`${field}-error`} role="alert">{error}</p>}
  </fieldset>;
}

/** 原版纸面语言下的三步创角前端；所有选择只保存为本机草稿，不生成游戏事实或调用模型。 */
export function CharacterCreationPage() {
  const [initial] = useState(readDraft);
  const [draft, setDraft] = useState(initial.draft);
  const [step, setStep] = useState<Step>(initial.step);
  const [visited, setVisited] = useState<Step>(initial.visited);
  const [errors, setErrors] = useState<Errors>({});
  const [storageError, setStorageError] = useState("");
  const [saved, setSaved] = useState(false);
  const [rootOpen, setRootOpen] = useState(false);
  const [rootChoice, setRootChoice] = useState(draft.root);
  const [customRoot, setCustomRoot] = useState("");
  const [rootError, setRootError] = useState("");
  const [reviewOpen, setReviewOpen] = useState(false);
  const [announcement, setAnnouncement] = useState("");
  const scroll = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const rootTrigger = useRef<HTMLButtonElement>(null);
  const stepChanged = useRef(false);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, draft, step, visited }));
      setStorageError("");
    } catch {
      setStorageError("草稿暂时无法保存在本机，请保留当前页面。 ".trim());
    }
  }, [draft, step, visited]);

  useEffect(() => {
    scroll.current?.scrollTo({ top: 0 });
    if (stepChanged.current) heading.current?.focus({ preventScroll: true });
    stepChanged.current = true;
  }, [step]);

  useEffect(() => {
    const field = Object.keys(errors)[0];
    if (!field) return;
    const selector = field === "name" ? "#cc-name" : field === "goal" ? "#cc-goal" : `[name="${field}"]`;
    const control = scroll.current?.querySelector<HTMLElement>(selector);
    control?.focus();
    control?.closest(".cc-field")?.scrollIntoView({ block: "center" });
  }, [errors]);

  function patch(value: Partial<Draft>) {
    setDraft(current => ({ ...current, ...value }));
    setErrors({}); setSaved(false); setAnnouncement("");
  }

  function validate(target: Step): Errors {
    const result: Errors = {};
    if (target === 0 && [...draft.name.trim()].length < 2) result.name = "姓名请写两到六个字。";
    if (target === 1) {
      if (!selectedValue(draft, "origin", ORIGINS)) result.origin = "请选择或填写出身。";
      if (!selectedValue(draft, "specialty", SPECIALTIES)) result.specialty = "请选择或填写一项所长。";
    }
    if (target === 2) {
      if (!selectedValue(draft, "motivation", MOTIVATIONS)) result.motivation = "请选择或填写修行原因。";
      if (!draft.goal.trim()) result.goal = "请写下当前目标，或选择一项。";
    }
    return result;
  }

  function move(target: Step) {
    if (target > step) {
      for (let index = 0; index < target; index++) {
        const nextErrors = validate(index as Step);
        if (Object.keys(nextErrors).length) {
          setErrors(nextErrors); setStep(index as Step); return;
        }
      }
    }
    setErrors({}); setStep(target); setVisited(current => Math.max(current, target) as Step); setSaved(false);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (step < 2) { move((step + 1) as Step); return; }
    for (const index of [0, 1, 2] as const) {
      const nextErrors = validate(index);
      if (Object.keys(nextErrors).length) { setErrors(nextErrors); setStep(index); return; }
    }
    setErrors({}); setReviewOpen(true);
  }

  function randomizeName() {
    const pool = NAMES[draft.gender].filter(name => name !== draft.name.trim());
    const nextName = pool[Math.floor(Math.random() * pool.length)];
    patch({ name: nextName });
    setAnnouncement(`姓名已换为${nextName}。`);
  }

  function showRoots() {
    const known = ROOTS.includes(draft.root);
    setRootChoice(known ? draft.root : "custom"); setCustomRoot(known ? "" : draft.root); setRootError(""); setRootOpen(true);
  }

  function saveRoot(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = rootChoice === "custom" ? customRoot.trim() : rootChoice;
    if (!value) { setRootError("请写下灵根，或选择随缘而定。"); return; }
    patch({ root: value }); setRootOpen(false);
  }

  function saveDraft() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, draft: { ...draft, name: draft.name.trim(), goal: draft.goal.trim() }, step, visited }));
      setReviewOpen(false); setStorageError(""); setSaved(true);
    } catch {
      setStorageError("草稿暂时无法保存在本机，请保留当前页面。 ".trim());
    }
  }

  function edit(target: Step) { setReviewOpen(false); move(target); }
  const background = BACKGROUNDS[draft.origin];
  const training = TRAINING[draft.specialty];
  const pursuit = PURSUITS[draft.motivation];
  const detailRows = [["年龄", draft.age], ["外貌", draft.appearance], ["性格", draft.personality]].filter(([, value]) => value.trim());

  return <div className="character-creation-page">
    <div className="cc-paper" aria-hidden="true" />
    <header className="cc-header"><div><strong>问仙录</strong></div></header>
    <div className="cc-scroll" ref={scroll}>
      <main className="cc-main">
        <section className="cc-sheet" aria-labelledby="cc-title">
          <h1 id="cc-title" ref={heading} tabIndex={-1}>创建人物</h1>
          <nav className="cc-steps" aria-label="创角步骤">
            {STEPS.map((label, index) => <button type="button" key={label} aria-current={step === index ? "step" : undefined} disabled={index > visited} onClick={() => move(index as Step)}>
              <span aria-hidden="true">{index < step ? <CheckIcon /> : ["一", "二", "三"][index]}</span>{label}
            </button>)}
          </nav>

          <form onSubmit={submit} noValidate>
            <div className="cc-step-panel" key={step}>
              {step === 0 && <>
                <div className="cc-field">
                  <label htmlFor="cc-name">姓名</label>
                  <div className="cc-name-row">
                    <input id="cc-name" value={draft.name} maxLength={6} autoComplete="off" aria-invalid={Boolean(errors.name)} aria-describedby={errors.name ? "cc-name-error" : undefined} onChange={event => patch({ name: event.target.value })} />
                    <button className="cc-secondary" type="button" onClick={randomizeName}><ReloadIcon aria-hidden="true" />随机取名</button>
                  </div>
                  {errors.name && <p className="cc-error" id="cc-name-error" role="alert">{errors.name}</p>}
                </div>
                <fieldset className="cc-field"><legend>性别</legend><div className="cc-segments">
                  {GENDERS.map(value => <label key={value} data-selected={draft.gender === value}><input type="radio" name="gender" value={value} checked={draft.gender === value} onChange={() => patch({ gender: value })} /><span>{value}</span></label>)}
                </div></fieldset>
                <div className="cc-race"><span>种族</span><strong>人族</strong></div>
              </>}

              {step === 1 && <>
                <div className="cc-selection-layout" data-context={Boolean(background)}>
                <ChoiceList title="出身" field="origin" options={ORIGINS} draft={draft} onChange={patch} error={errors.origin} compact summaryOnly />
                {background && <article className="cc-context" aria-label="出身经历">
                  <h2>{ORIGINS.find(option => option.id === draft.origin)?.name}</h2>
                  <p>{ORIGINS.find(option => option.id === draft.origin)?.description}</p>
                  <dl><div><dt>熟悉</dt><dd>{background.familiar}</dd></div><div><dt>旧识</dt><dd>{background.contacts}</dd></div></dl>
                </article>}
                </div>
                <div className="cc-field"><label htmlFor="cc-bond">牵挂与旧识（选填）</label>
                  {background && <div className="cc-goals cc-bond-suggestions" role="group" aria-label="牵挂建议">{background.bonds.map(bond => <button type="button" key={bond} aria-pressed={draft.bond === bond} onClick={() => patch({ bond })}>{bond}</button>)}</div>}
                  <textarea id="cc-bond" className="cc-textarea" rows={2} maxLength={240} value={draft.bond} onChange={event => patch({ bond: event.target.value })} />
                </div>
                <div className="cc-selection-layout" data-context={Boolean(training)}>
                <ChoiceList title="初始所长" field="specialty" options={SPECIALTIES} draft={draft} onChange={patch} error={errors.specialty} compact summaryOnly />
                {training && <article className="cc-context" aria-label="所长用途"><h2>遇事时怎么用</h2><p>{training.use}</p><dl><div><dt>范围</dt><dd>{training.scope}</dd></div></dl></article>}
                </div>
              </>}

              {step === 2 && <>
                <div className="cc-field"><label htmlFor="cc-root">灵根</label><button id="cc-root" ref={rootTrigger} className="cc-root-trigger" type="button" onClick={showRoots} aria-haspopup="dialog"><span>{draft.root}</span><ChevronDownIcon aria-hidden="true" /></button></div>
                <div className="cc-selection-layout" data-context={Boolean(pursuit)}>
                <ChoiceList title="修行原因" field="motivation" options={MOTIVATIONS} draft={draft} onChange={patch} error={errors.motivation} compact summaryOnly />
                {pursuit && <article className="cc-context cc-pursuit" aria-label="追寻方向"><h2>你想追寻的事</h2><p>{pursuit}</p></article>}
                </div>
                <div className="cc-field"><label htmlFor="cc-goal">当前目标</label>
                  <div className="cc-goals" role="group" aria-label="目标建议">{(GOALS[draft.motivation] ?? ["寻找师承", "谋份生计", "结识当地修士"]).map(goal => <button type="button" key={goal} aria-pressed={draft.goal === goal} onClick={() => patch({ goal })}>{goal}</button>)}</div>
                  <textarea id="cc-goal" className="cc-textarea" rows={3} maxLength={240} value={draft.goal} aria-invalid={Boolean(errors.goal)} aria-describedby={errors.goal ? "cc-goal-error" : undefined} onChange={event => patch({ goal: event.target.value })} />
                  {errors.goal && <p className="cc-error" id="cc-goal-error" role="alert">{errors.goal}</p>}
                </div>
                <details className="cc-details"><summary>补充设定（选填）<ChevronDownIcon aria-hidden="true" /></summary><div>
                  <label>年龄<input value={draft.age} maxLength={24} onChange={event => patch({ age: event.target.value })} /></label>
                  <label>外貌<textarea rows={2} maxLength={240} value={draft.appearance} onChange={event => patch({ appearance: event.target.value })} /></label>
                  <label>性格<textarea rows={2} maxLength={240} value={draft.personality} onChange={event => patch({ personality: event.target.value })} /></label>
                </div></details>
              </>}
            </div>
            {storageError && <p className="cc-error" role="alert">{storageError}</p>}
            {saved && <p className="cc-saved" role="status"><CheckIcon aria-hidden="true" />人物草稿已保存</p>}
            <footer className="cc-actions">
              {step > 0 && <button type="button" className="cc-secondary" onClick={() => move((step - 1) as Step)}><ArrowLeftIcon aria-hidden="true" />上一步</button>}
              <button type="submit" className="cc-primary">{step === 2 ? "确认人物" : "下一步"}<ArrowRightIcon aria-hidden="true" /></button>
            </footer>
          </form>
          <span className="cc-screen-reader" role="status">{announcement}</span>
        </section>
      </main>
    </div>

    <Dialog.Root open={rootOpen} onOpenChange={setRootOpen}><Dialog.Portal>
      <Dialog.Overlay className="cc-overlay" />
      <Dialog.Content className="cc-dialog" aria-describedby={undefined} onCloseAutoFocus={event => { event.preventDefault(); rootTrigger.current?.focus(); }}>
        <Dialog.Title className="cc-dialog-title">灵根</Dialog.Title><Dialog.Close className="cc-close" aria-label="关闭灵根选择"><Cross2Icon /></Dialog.Close>
        <form onSubmit={saveRoot}>
          <div className="cc-root-options">{[...ROOTS, "custom"].map(value => <label key={value} data-selected={rootChoice === value}>
            <input type="radio" name="root" value={value} checked={rootChoice === value} onChange={() => { setRootChoice(value); setRootError(""); }} />
            <span>{value === "custom" ? "自定灵根" : value}</span>{rootChoice === value && <CheckIcon aria-hidden="true" />}
          </label>)}</div>
          {rootChoice === "custom" && <label className="cc-root-custom">自定灵根<input value={customRoot} maxLength={16} onChange={event => { setCustomRoot(event.target.value); setRootError(""); }} /></label>}
          {rootError && <p className="cc-error" role="alert">{rootError}</p>}
          <div className="cc-dialog-actions"><Dialog.Close asChild><button className="cc-secondary" type="button">取消</button></Dialog.Close><button className="cc-primary" type="submit">选定</button></div>
        </form>
      </Dialog.Content>
    </Dialog.Portal></Dialog.Root>

    <Dialog.Root open={reviewOpen} onOpenChange={setReviewOpen}><Dialog.Portal>
      <Dialog.Overlay className="cc-overlay" />
      <Dialog.Content className="cc-dialog cc-review" aria-describedby={undefined}>
        <Dialog.Title className="cc-dialog-title">确认人物</Dialog.Title><Dialog.Close className="cc-close" aria-label="关闭人物确认"><Cross2Icon /></Dialog.Close>
        <div className="cc-review-name">{draft.name.trim()}</div>
        <div className="cc-review-identity">{draft.gender}<span>·</span>人族</div>
        <section><div className="cc-review-section"><h2>来历</h2><button type="button" aria-label="修改来历" onClick={() => edit(1)}>修改</button></div><dl>
          <div><dt>出身</dt><dd>{selectedValue(draft, "origin", ORIGINS)}</dd></div><div><dt>所长</dt><dd>{selectedValue(draft, "specialty", SPECIALTIES)}</dd></div>
          {background && <><div><dt>熟悉</dt><dd>{background.familiar}</dd></div><div><dt>旧识</dt><dd>{background.contacts}</dd></div></>}
          {draft.bond.trim() && <div><dt>牵挂</dt><dd>{draft.bond.trim()}</dd></div>}
        </dl></section>
        <section><div className="cc-review-section"><h2>求道</h2><button type="button" aria-label="修改求道" onClick={() => edit(2)}>修改</button></div><dl>
          <div><dt>灵根</dt><dd>{draft.root}</dd></div><div><dt>缘由</dt><dd>{selectedValue(draft, "motivation", MOTIVATIONS)}</dd></div><div><dt>目标</dt><dd>{draft.goal.trim()}</dd></div>
        </dl></section>
        {detailRows.length > 0 && <section><h2>补充设定</h2><dl>{detailRows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></section>}
        {storageError && <p className="cc-error" role="alert">{storageError}</p>}
        <div className="cc-dialog-actions"><button className="cc-secondary" type="button" onClick={() => edit(0)}>修改人物</button><button className="cc-primary" type="button" onClick={saveDraft}>保存草稿</button></div>
      </Dialog.Content>
    </Dialog.Portal></Dialog.Root>
  </div>;
}
