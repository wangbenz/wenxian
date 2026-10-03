import { test, expect } from "vitest";
import { mountSuspended } from "@nuxt/test-utils/runtime";
import ReferenceEntry from "~/components/ReferenceEntry.vue";

test("chapter navigation has actual targets while the Markdown safety boundary remains active", async () => {
  const page = await mountSuspended(ReferenceEntry, {
    props: {
      kind: "realm",
      entry: {
        name: "筑基",
        reading_neighbors: {
          previous: { key: "xianxia_realm-qi-refining", name: "炼气" },
          next: { key: "xianxia_realm-core-formation", name: "结丹" },
        },
        document: { key: "xianxia-core", name: "凡人流修仙资料" } as any,
        desc: "**定义。** 保留原文。\n\n### 来源依据\n\n<script>alert(1)</script>[坏链接](javascript:alert(1))\n\n[实际出处](https://example.org)",
      },
    },
  });
  expect(page.find("h1").text()).toBe("筑基");
  const neighbors = page.find('[aria-label="境界衔接"]');
  expect(neighbors.text()).toContain("下一境界结丹");
  expect(neighbors.find(".realm-reading-next").attributes("href")).toBe(
    "/rules/entries/xianxia_realm-core-formation"
  );
  for (const link of page.findAll('[aria-label="本页目录"] a')) {
    expect(page.find(link.attributes("href")!).exists()).toBe(true);
  }
  expect(page.text()).toContain("保留原文。");
  expect(page.html()).not.toContain("<script");
  expect(page.findAll('a[href^="javascript:"]')).toHaveLength(0);
  expect(page.find("a.reference-kind").attributes("href")).toBe(
    "/library?kind=realm"
  );
  page.unmount();
});
