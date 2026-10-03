import { test, expect } from "vitest";
import { mountSuspended } from "@nuxt/test-utils/runtime";
import MdViewer from "~/components/MdViewer.vue";

test("正文只呈现Markdown，不执行Vue表达式或不安全HTML", async () => {
  const page = await mountSuspended(MdViewer, {
    props: {
      text: '## 资料\n\n{{ 7 * 7 }}\n\n<script>alert(1)</script><img src="x" onerror="alert(2)"><a href="javascript:alert(3)">链接</a>',
    },
  });
  expect(page.find("h2").text()).toBe("资料");
  expect(page.text()).toContain("{{ 7 * 7 }}");
  expect(page.html()).not.toContain("<script");
  expect(page.html()).not.toContain("onerror");
  expect(page.html()).not.toContain("javascript:");
});

test("保留表格和标题层级，行内正文不创建段落", async () => {
  const block = await mountSuspended(MdViewer, {
    props: { text: "# 标题\n\n| 名称 | 类型 |\n| --- | --- |\n| 火弹术 | 法术 |", headerLevel: 3 },
  });
  expect(block.find("h3").text()).toBe("标题");
  expect(block.find("table").text()).toContain("火弹术");
  const inline = await mountSuspended(MdViewer, { props: { text: "**法术**", inline: true } });
  expect(inline.find("strong").text()).toBe("法术");
  expect(inline.find("p").exists()).toBe(false);
});
