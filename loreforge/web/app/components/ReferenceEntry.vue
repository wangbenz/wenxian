<template>
  <article class="reference-entry">
    <LibraryBackLink :fallback="categoryHref" />
    <header class="reference-heading">
      <NuxtLink :to="categoryHref" class="reference-kind">{{
        category?.name || kindName || "修仙资料"
      }}</NuxtLink>
      <h1>{{ entry.name }}</h1>
      <p class="reference-source">
        收录于
        <NuxtLink :to="`/sources/${entry.document.key}`">{{
          entry.document.name
        }}</NuxtLink>
      </p>
    </header>
    <nav
      v-if="entry.reading_neighbors"
      class="realm-reading-neighbors"
      aria-label="境界衔接"
    >
      <NuxtLink
        v-if="entry.reading_neighbors.previous"
        :to="`/rules/entries/${entry.reading_neighbors.previous.key}`"
        ><span>上一境界</span
        ><strong>{{ entry.reading_neighbors.previous.name }}</strong></NuxtLink
      >
      <NuxtLink
        v-if="entry.reading_neighbors.next"
        class="realm-reading-next"
        :to="`/rules/entries/${entry.reading_neighbors.next.key}`"
        ><span>下一境界</span
        ><strong>{{ entry.reading_neighbors.next.name }}</strong></NuxtLink
      >
    </nav>
    <div class="reference-layout">
      <details v-if="sections.length > 1" class="reference-index-mobile">
        <summary>
          本页目录 <span>{{ sections.length }} 节</span>
        </summary>
        <nav aria-label="本页目录">
          <a
            v-for="section in sections"
            :key="section.id"
            :href="`#${section.id}`"
            >{{ section.title }}</a
          >
        </nav>
      </details>
      <div class="reference-body" @click="followReference">
        <section
          v-for="(section, index) in sections"
          :key="section.id"
          :aria-labelledby="section.id"
          :class="{ 'reference-lead': index === 0 }"
        >
          <h2 :id="section.id">{{ section.title }}</h2>
          <MdViewer :text="section.text" />
        </section>
        <slot />
        <footer class="reference-end">
          <NuxtLink :to="categoryHref"
            >继续查阅{{ category?.name || kindName || "同类资料" }}
            <Icon name="chevron-right" /></NuxtLink
          ><a href="#main-content">回到顶部</a>
        </footer>
      </div>
      <aside v-if="sections.length > 1" class="reference-index-desktop">
        <nav aria-label="本页目录">
          <p>本页目录</p>
          <a
            v-for="section in sections"
            :key="section.id"
            :href="`#${section.id}`"
            >{{ section.title }}</a
          >
        </nav>
      </aside>
    </div>
  </article>
</template>
<script setup lang="ts">
import type { DocumentSummary, ReadingNeighbors } from "@/types";
import { libraryKind, libraryHref } from "@/helpers/library";
import { referenceSections } from "@/helpers/referenceSections";
const props = defineProps<{
  entry: {
    name: string;
    desc?: string;
    document: DocumentSummary;
    reading_neighbors?: ReadingNeighbors | null;
  };
  kind?: string;
  kindName?: string;
}>();
const category = computed(() => libraryKind(props.kind));
const categoryHref = computed(() =>
  libraryHref(props.kind, props.entry.document.key)
);
const sections = computed(() => referenceSections(props.entry.desc));
function followReference(event: MouseEvent) {
  if (
    event.button ||
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey ||
    event.defaultPrevented
  )
    return;
  const anchor = (event.target as Element).closest("a");
  const href = anchor?.getAttribute("href");
  if (
    href?.startsWith("/") &&
    !href.startsWith("//") &&
    !anchor?.hasAttribute("target")
  ) {
    event.preventDefault();
    navigateTo(href);
  }
}
</script>
