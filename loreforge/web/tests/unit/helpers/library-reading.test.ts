import { describe, expect, test } from "vitest";
import { referenceSections } from "~/helpers/referenceSections";
import {
  catalogueKindName,
  libraryHref,
  readLibraryQuery,
  writeLibraryQuery,
} from "~/helpers/library";

describe("reference reading preserves authored paragraphs", () => {
  test("paragraph labels and headings become navigable sections without losing body or links", () => {
    const sections = referenceSections(
      "**定义。** 第一段。\n\n第二段仍是定义。\n\n**常规准备。** 条件原文。\n\n### 来源依据\n\n[出处](https://example.org)"
    );
    expect(sections.map((section) => section.title)).toEqual([
      "定义",
      "常规准备",
      "来源依据",
    ]);
    expect(sections[0].text).toBe("第一段。\n\n第二段仍是定义。");
    expect(sections[1].text).toBe("条件原文。");
    expect(sections[2].text).toBe("[出处](https://example.org)");
    expect(new Set(sections.map((section) => section.id)).size).toBe(3);
  });
  test("plain text and code samples are preserved", () => {
    const plain = "没有小标题的正文。\n\n保留第二段。";
    expect(referenceSections(plain)[0].text).toBe(plain);
    const code = "```md\n## 代码里的标题\n\n**标签。** 不是章节\n```";
    expect(referenceSections(code)).toHaveLength(1);
    expect(referenceSections(code)[0].text).toBe(code);
    expect(referenceSections("")).toEqual([]);
  });
});

describe("shareable catalogue filters", () => {
  test("source, search, category and page survive a URL round trip", () => {
    expect(catalogueKindName("item", "xianxia-core")).toBe("修行器具");
    expect(catalogueKindName("item", "srd-2024")).toBe("物品");
    expect(catalogueKindName("rule", "srd-2024")).toBe("规则");
    const state = { q: "筑基丹", kind: "pill", source: "all", page: 2 };
    expect(readLibraryQuery(writeLibraryQuery(state))).toEqual(state);
    expect(libraryHref("realm")).toBe("/library?kind=realm");
    expect(libraryHref("spell", "srd-2024")).toBe(
      "/library?kind=spell&source=srd-2024"
    );
  });
  test("invalid parameters do not become invented categories or negative pages", () => {
    expect(
      readLibraryQuery({ kind: "godmode", page: "-2", source: ["x", "y"] })
    ).toEqual({ q: "", kind: "", source: "xianxia-core", page: 1 });
    expect(
      readLibraryQuery({ page: "1.2", q: "字".repeat(201) }).q
    ).toHaveLength(200);
  });
});
