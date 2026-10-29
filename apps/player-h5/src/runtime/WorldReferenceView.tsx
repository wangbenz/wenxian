import { useEffect, useRef, useState } from "react";
import { ArrowLeftIcon } from "@radix-ui/react-icons";
import { accountRuntimeFetch, runtimeFetch, type ReferenceCategory, type ReferenceDetail, type ReferenceOverview, type ReferencePage } from "./runtime-api";

const CATEGORIES = {
  realms: { name: "修行境界", short: "境界" },
  arts: { name: "功法与术法", short: "功法术法" },
  items: { name: "物品", short: "物品" },
  "place-types": { name: "场所", short: "场所" },
  "facility-types": { name: "设施", short: "设施" },
  recipes: { name: "配方", short: "配方" },
} satisfies Record<ReferenceCategory, { name: string; short: string }>;
const DEFAULT_CATEGORIES = Object.entries(CATEGORIES).map(([id, row]) => ({ id: id as ReferenceCategory, name: row.name }));

function ReadError({ message, retry }: { message: string; retry: () => void }) {
  return <div className="reference-error" role="alert"><p>{message}</p><button onClick={retry} type="button">重新读取</button></div>;
}

/** 设置内的只读资料菜单；按当前分类/条目取数，迟到响应不能覆盖新页面，也不更新命卷或模型上下文。 */
export function WorldReferenceView({ token, accountSaveId, onBack }: { token: string; accountSaveId?: string; onBack: () => void }) {
  function read<T>(path: string) { return accountSaveId ? accountRuntimeFetch<T>(path, accountSaveId) : runtimeFetch<T>(path, token); }
  const [initial] = useState(() => {
    const params = new URL(window.location.href).searchParams;
    const id = params.get("reference");
    const category = id && Object.hasOwn(CATEGORIES, id) ? id as ReferenceCategory : null;
    const offset = Number(params.get("refOffset") ?? 0);
    const key = params.get("refEntry");
    return {
      category,
      offset: category && Number.isInteger(offset) && offset >= 0 && offset <= 1000000 ? offset : 0,
      key: category && key && /^[a-z0-9][a-z0-9:_-]{0,99}$/.test(key) ? key : null,
    };
  });
  const heading = useRef<HTMLHeadingElement>(null);
  const [overview, setOverview] = useState<ReferenceOverview | null>(null);
  const [overviewFailed, setOverviewFailed] = useState(false);
  const [category, setCategory] = useState<ReferenceCategory | null>(initial.category);
  const [offset, setOffset] = useState(initial.offset);
  const [page, setPage] = useState<ReferencePage | null>(null);
  const [pageFailed, setPageFailed] = useState(false);
  const [selected, setSelected] = useState<string | null>(initial.key);
  const [detail, setDetail] = useState<ReferenceDetail | null>(null);
  const [detailFailed, setDetailFailed] = useState(false);
  const [retry, setRetry] = useState(0);
  const reload = () => setRetry(value => value + 1);
  const title = selected ? detail?.name ?? "资料详情" : category ? CATEGORIES[category].name : "世界与规则";

  useEffect(() => {
    const url = new URL(window.location.href);
    url.searchParams.set("reference", category ?? "world");
    // 旧搜索链接仍能进入对应类别；资料菜单不保留搜索条件。
    url.searchParams.delete("refQuery");
    for (const [name, value] of [["refOffset", category && offset ? String(offset) : ""], ["refEntry", selected ?? ""]]) {
      if (value) url.searchParams.set(name, value); else url.searchParams.delete(name);
    }
    window.history.replaceState(null, "", url);
  }, [category, offset, selected]);
  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    heading.current?.closest(".game-scroll")?.scrollTo({ top: 0 });
  }, [category, offset, selected]);
  useEffect(() => {
    let current = true; setOverviewFailed(false);
    read<ReferenceOverview>("/reference").then(value => { if (current) setOverview(value); }).catch(() => { if (current) setOverviewFailed(true); });
    return () => { current = false; };
  }, [token, accountSaveId, retry]);
  useEffect(() => {
    if (!category) return;
    let current = true; setPage(null); setPageFailed(false);
    read<ReferencePage>(`/reference/entries/${category}?offset=${offset}`).then(value => { if (current) setPage(value); }).catch(() => { if (current) setPageFailed(true); });
    return () => { current = false; };
  }, [token, accountSaveId, category, offset, retry]);
  useEffect(() => {
    if (!selected || !category) return;
    let current = true; setDetail(null); setDetailFailed(false);
    read<ReferenceDetail>(`/reference/entries/${category}/${encodeURIComponent(selected)}`).then(value => { if (current) setDetail(value); }).catch(() => { if (current) setDetailFailed(true); });
    return () => { current = false; };
  }, [token, accountSaveId, category, selected, retry]);

  const chooseCategory = (id: ReferenceCategory | null) => {
    if (category === id && offset === 0 && !selected) return;
    if (category !== id || offset !== 0) setPage(null);
    setCategory(id); setOffset(0); setSelected(null);
  };
  const back = () => selected ? setSelected(null) : onBack();
  return <div className="record-view reference-view">
    <button className="character-library-back reference-back" onClick={back} type="button"><ArrowLeftIcon aria-hidden="true" /><span>{selected ? "返回资料目录" : "返回设置"}</span></button>
    <header className="reference-heading"><h1>世界资料</h1></header>
    <div className="reference-layout">
      <nav className="reference-category-nav" aria-label="资料分类">
        <button aria-current={!category ? "page" : undefined} onClick={() => chooseCategory(null)} type="button">世界与规则</button>
        {(overview?.categories ?? DEFAULT_CATEGORIES).map(row => <button key={row.id} aria-label={row.name} aria-current={category === row.id ? "page" : undefined} onClick={() => chooseCategory(row.id)} type="button">{CATEGORIES[row.id].short}</button>)}
      </nav>
      <section className="reference-content" aria-label="资料内容">
        <div className="reference-content-heading"><h2 ref={heading} tabIndex={-1}>{title}</h2>{!selected && category && page && <span>共 {page.count} 条</span>}</div>
        {!category && (overviewFailed ? <ReadError message="世界资料暂时无法读取，可以稍后再来查看。" retry={reload} /> : !overview ? <p role="status">正在读取世界资料…</p> : <>
          <section className="reference-world-intro"><small>{overview.world.scope}</small><h3>{overview.world.title}</h3><p>{overview.world.summary}</p></section>
          {overview.world.sections.map(row => <section className="reference-section" key={row.title}><h3>{row.title}</h3><p>{row.text}</p></section>)}
        </>)}
        {!selected && category && (pageFailed ? <ReadError message="目录暂时无法读取，命卷和故事不受影响。" retry={reload} /> : !page ? <p role="status">正在读取资料目录…</p> : <>
          {page.results.length === 0 ? <div className="reference-empty"><p>此类资料尚未收录</p><span>可切换其他类别查看。</span></div> : <ul className="reference-list">{page.results.map(row => <li key={row.key}><button onClick={() => { setDetail(null); setDetailFailed(false); setSelected(row.key); }} type="button"><span className="reference-entry-title">{row.name}</span>{row.example && <small>测试资料</small>}<span className="reference-entry-description">{row.description}</span></button></li>)}</ul>}
          {page.count > page.limit && <nav className="reference-pagination" aria-label="资料翻页"><button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - page.limit))} type="button">上一页</button><span>第 {Math.floor(offset / page.limit) + 1} / {Math.ceil(page.count / page.limit)} 页</span><button disabled={offset + page.limit >= page.count} onClick={() => setOffset(offset + page.limit)} type="button">下一页</button></nav>}
        </>)}
        {selected && (detailFailed ? <ReadError message="这条资料暂时无法读取，仍可返回目录或设置。" retry={reload} /> : !detail ? <p role="status">正在读取这条资料…</p> : <article className="reference-detail" aria-label="资料详情">
          <div className="reference-version"><span>{detail.kind}</span><span>{detail.scope === "save" ? "本局采用的资料" : "公共资料当前版"} · 第 {detail.revision} 版</span></div>
          {detail.example && <p className="reference-example" role="note">测试资料，仅供本轮试玩参考。</p>}
          {detail.sections.map(row => <section className="reference-section" key={row.title}><h3>{row.title}</h3><p>{row.text}</p></section>)}
        </article>)}
        <p className="reference-note">资料仅供查阅，不改变角色的持有、所学或经历。</p>
      </section>
    </div>
  </div>;
}
