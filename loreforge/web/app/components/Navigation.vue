<template>
  <nav class="library-navigation" aria-label="修仙资料分类">
    <NuxtLink
      to="/library"
      class="brand-block"
      @click="$emit('on-link-clicked')"
      ><strong>开物</strong><span>修仙资料库</span></NuxtLink
    >
    <div class="navigation-scroll">
      <NuxtLink
        to="/library"
        class="navigation-overview"
        :class="{ 'is-current': route.path === '/library' && !selected.kind }"
        :aria-current="
          route.path === '/library' && !selected.kind ? 'page' : undefined
        "
        @click="$emit('on-link-clicked')"
        ><Icon name="book" /> 全部资料</NuxtLink
      >
      <section
        v-for="group in libraryGroups"
        :key="group.name"
        class="navigation-group"
      >
        <h2>{{ group.name }}</h2>
        <ul>
          <li v-for="kind in group.kinds" :key="kind">
            <NuxtLink
              :to="libraryHref(kind)"
              :class="{
                'is-current':
                  route.path === '/library' && selected.kind === kind,
              }"
              :aria-current="
                route.path === '/library' && selected.kind === kind
                  ? 'page'
                  : undefined
              "
              @click="$emit('on-link-clicked')"
              >{{ libraryKind(kind)?.name }}<Icon name="chevron-right"
            /></NuxtLink>
          </li>
        </ul>
      </section>
      <div class="navigation-references">
        <NuxtLink to="/sources" @click="$emit('on-link-clicked')"
          ><Icon name="document" /> 资料来源</NuxtLink
        >
        <details>
          <summary>其他资料入口</summary>
          <ul>
            <li v-for="page in referencePages" :key="page.path">
              <NuxtLink :to="page.path" @click="$emit('on-link-clicked')">{{
                page.name
              }}</NuxtLink>
            </li>
          </ul>
        </details>
      </div>
    </div>
  </nav>
</template>
<script setup lang="ts">
import {
  libraryGroups,
  libraryKind,
  libraryHref,
  readLibraryQuery,
} from "@/helpers/library";
defineEmits(["on-link-clicked"]);
const route = useRoute();
const selected = computed(() => readLibraryQuery(route.query));
const referencePages = [
  { name: "法术原分类", path: "/spells" },
  { name: "物品原分类", path: "/equipment" },
  { name: "魔法物品原分类", path: "/magic-items" },
  { name: "规则章节", path: "/rules" },
  { name: "状态说明", path: "/conditions" },
];
</script>
