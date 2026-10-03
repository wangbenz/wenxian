/** 展示分类与URL状态；仅组织资料阅读，不解释修行条件或执行游戏规则。 */
export const libraryGroups = [
  { name: "修行入门", kinds: ["realm", "practice", "rule"] },
  { name: "术法与器物", kinds: ["spell", "artifact", "talisman", "pill"] },
  { name: "天地见闻", kinds: ["material", "creature", "setting", "item"] },
];
export const libraryKinds = [
  { key: "realm", name: "境界", description: "境界定义、突破准备与修行变化。" },
  {
    key: "practice",
    name: "功法",
    description: "吐纳、导引与行功方法，查阅修习条件和边界。",
  },
  {
    key: "rule",
    name: "修行通则",
    description: "灵根、器物分类与修仙世界的基本约定。",
  },
  {
    key: "spell",
    name: "法术",
    description: "从入门术法到变化遁行，查阅用途、条件与代价。",
  },
  {
    key: "artifact",
    name: "法器",
    description: "器物的用途、驱使方式、限制与养护。",
  },
  {
    key: "talisman",
    name: "符箓",
    description: "灵符的作用、使用条件与消耗。",
  },
  {
    key: "pill",
    name: "丹药",
    description: "修习与突破中的丹药，辨明作用和适用条件。",
  },
  {
    key: "material",
    name: "灵材",
    description: "灵石、草木与炼制材料，查阅性质与用途。",
  },
  {
    key: "creature",
    name: "妖兽",
    description: "异兽形貌、行为、栖居环境与古典出处。",
  },
  {
    key: "setting",
    name: "山门与洞府",
    description: "修行场所、宗门与坊市，以及机关禁制。",
  },
  {
    key: "item",
    name: "修行器具",
    description: "炼丹、炼器等修行活动中使用的工具。",
  },
];
export const libraryKind = (key?: string) =>
  libraryKinds.find((kind) => kind.key === key);
/** 修仙导航称呼只用于本来源；旧资料保持其通用分类含义。 */
export function catalogueKindName(key: string, source: string) {
  const generic: Record<string, string> = {
    item: "物品",
    rule: "规则",
    setting: "场所与社会",
  };
  return (source !== "xianxia-core" && generic[key]) || libraryKind(key)?.name;
}
export function libraryHref(kind = "", source = "xianxia-core") {
  const query = new URLSearchParams();
  if (kind) query.set("kind", kind);
  if (source !== "xianxia-core") query.set("source", source);
  return "/library" + (query.size ? "?" + query : "");
}
export type LibraryQuery = {
  q: string;
  kind: string;
  source: string;
  page: number;
};
export function readLibraryQuery(query: Record<string, unknown>): LibraryQuery {
  const scalar = (key: string) =>
    typeof query[key] === "string" ? (query[key] as string) : "";
  const number = Number(scalar("page") || 1);
  return {
    q: scalar("q").slice(0, 200),
    kind: libraryKind(scalar("kind"))?.key ?? "",
    source: scalar("source").slice(0, 100) || "xianxia-core",
    page: Number.isSafeInteger(number) && number > 0 ? number : 1,
  };
}
export function writeLibraryQuery(value: LibraryQuery): Record<string, string> {
  return {
    ...(value.q ? { q: value.q } : {}),
    ...(value.kind ? { kind: value.kind } : {}),
    ...(value.source !== "xianxia-core" ? { source: value.source } : {}),
    ...(value.page > 1 ? { page: String(value.page) } : {}),
  };
}
