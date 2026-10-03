<template>
  <div :class="inline ? 'markdown markdown-inline' : 'markdown'" v-html="rendered" />
</template>
<script setup lang="ts">
import MarkdownIt from "markdown-it";
import sanitizeHtml from "sanitize-html";
// 资料只按 Markdown 内容呈现，不编译 Vue 模板，不生成掷骰或其他执行型控件。
const props = defineProps<{
  text?: string;
  headerLevel?: number;
  inline?: boolean;
  crossreferences?: unknown[];
}>();
const rendered = computed(() => {
  const parser = new MarkdownIt({ html: true, breaks: true });
  const offset = Math.max(0, (props.headerLevel ?? 1) - 1);
  parser.core.ruler.push("heading-level", (state) => {
    for (const token of state.tokens) {
      if (token.type === "heading_open" || token.type === "heading_close") {
        token.tag = `h${Math.min(6, Number(token.tag.slice(1)) + offset)}`;
      }
    }
  });
  const html = props.inline
    ? parser.renderInline(props.text ?? "")
    : parser.render(props.text ?? "");
  return sanitizeHtml(html, {
        allowedTags: sanitizeHtml.defaults.allowedTags,
        allowedAttributes: {
          a: ["href", "name", "target", "rel"],
          th: ["align"],
          td: ["align"],
        },
        allowedSchemes: ["http", "https", "mailto"],
        allowProtocolRelative: false,
  });
});
</script>
<style>
.markdown {
  overflow-wrap: anywhere;
}
.markdown ul {
  list-style-type: disc;
  margin-left: 1rem;
}
.markdown ol {
  list-style-type: decimal;
  margin-left: 1rem;
}
.markdown a {
  color: #1d6068;
  text-decoration: underline;
}
.markdown-inline,
.markdown-inline p,
.markdown-inline div {
  display: inline;
}
</style>
