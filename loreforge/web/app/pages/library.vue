<template>
  <section class="library-page" aria-labelledby="library-title">
    <header class="library-heading">
      <p class="library-eyebrow">开物 · 资料查阅</p>
      <h1 id="library-title">{{ category?.name || "修仙资料库" }}</h1>
      <p>
        {{ category?.description || "从境界入门，查阅功法、术法与天地万物。" }}
      </p>
      <p v-if="query.kind === 'realm'" class="realm-order-note">
        按各来源的境界顺序排列。
        <NuxtLink
          v-if="query.source === 'xianxia-core'"
          to="/rules/xianxia_realm"
          >境界总览与飞升说明 <Icon name="chevron-right"
        /></NuxtLink>
      </p>
    </header>
    <form class="library-filters" role="search" @submit.prevent="submitSearch">
      <label class="library-search"
        ><span class="sr-only">搜索资料名称</span><Icon name="search" /><input
          v-model="search"
          name="q"
          type="search"
          maxlength="200"
          placeholder="搜名称，如筑基、火弹术、灵石"
          autocomplete="off"
      /></label>
      <div class="library-selects">
        <label
          >资料分类<select
            :value="query.kind"
            @change="
              update({ kind: ($event.target as HTMLSelectElement).value })
            "
          >
            <option value="">全部分类</option>
            <option
              v-for="kind in visibleKinds"
              :key="kind.key"
              :value="kind.key"
            >
              {{ kind.name }}
            </option>
          </select></label
        >
        <label
          >资料来源<select
            :value="query.source"
            @change="
              update({ source: ($event.target as HTMLSelectElement).value })
            "
          >
            <option value="all">全部来源</option>
            <option
              v-for="source in sourceFilter.options"
              :key="source.value"
              :value="source.value"
            >
              {{ source.name }}
            </option>
          </select></label
        >
      </div>
    </form>
    <div class="library-result-heading">
      <p role="status" aria-live="polite">
        {{ data ? `共 ${data.count} 条资料` : "正在读取资料…"
        }}<span v-if="isFetching && data"> · 正在更新</span>
      </p>
      <button v-if="query.q || query.kind" type="button" @click="clearFilters">
        清除名称与分类 <Icon name="x-mark" />
      </button>
    </div>
    <div v-if="error" class="library-message" role="alert">
      <h2>暂时无法读取这页资料</h2>
      <p>检索条件已保留，可以重新读取或回到第一页。</p>
      <button type="button" @click="refetch()">重新读取</button
      ><button v-if="query.page > 1" type="button" @click="update({ page: 1 })">
        回到第一页
      </button>
    </div>
    <div v-else-if="data && !data.results.length" class="library-message">
      <h2>没有找到符合条件的资料</h2>
      <p>试试更短的名称，或更换分类与来源。</p>
      <button type="button" @click="clearFilters">清除名称与分类</button>
    </div>
    <ol
      v-else-if="data"
      class="catalogue-list"
      aria-label="修仙资料目录"
      :aria-busy="isFetching"
    >
      <li v-for="entry in data.results" :key="`${entry.resource}:${entry.key}`">
        <NuxtLink :to="entry.page_url" class="catalogue-entry"
          ><div>
            <div class="catalogue-entry-title">
              <h2>{{ entry.name }}</h2>
              <span>{{
                catalogueKindName(entry.kind, entry.document.key) ||
                entry.kind_label
              }}</span>
            </div>
            <p class="catalogue-excerpt">{{ entry.excerpt }}</p>
            <span v-if="query.source === 'all'" class="catalogue-source">{{
              entry.document.name
            }}</span>
          </div>
          <Icon name="chevron-right" class="catalogue-arrow"
        /></NuxtLink>
      </li>
    </ol>
    <p v-else class="library-message" role="status">正在整理目录…</p>
    <nav
      v-if="data?.count && !error"
      class="library-pagination"
      aria-label="资料分页"
    >
      <span>第 {{ query.page }} / {{ lastPage }} 页</span>
      <div>
        <button
          :disabled="query.page <= 1 || isFetching"
          aria-label="上一页"
          @click="update({ page: query.page - 1 })"
        >
          <Icon name="chevron-left" /> 上一页</button
        ><button
          :disabled="query.page >= lastPage || isFetching"
          aria-label="下一页"
          @click="update({ page: query.page + 1 })"
        >
          下一页 <Icon name="chevron-right" />
        </button>
      </div>
    </nav>
  </section>
</template>
<script setup lang="ts">
import { useQuery, keepPreviousData } from "@tanstack/vue-query";
import { watchDebounced } from "@vueuse/core";
import {
  libraryKinds,
  catalogueKindName,
  readLibraryQuery,
  writeLibraryQuery,
  type LibraryQuery,
} from "@/helpers/library";
useSeoIndex({ title: "修仙资料库" });
const route = useRoute();
const router = useRouter();
const query = computed(() => readLibraryQuery(route.query));
const visibleKinds = computed(() =>
  libraryKinds.map((kind) => ({
    ...kind,
    name: catalogueKindName(kind.key, query.value.source),
    description:
      query.value.source === "xianxia-core"
        ? kind.description
        : "按当前来源查阅资料，定义与适用规则见各条说明。",
  }))
);
const category = computed(() =>
  visibleKinds.value.find((kind) => kind.key === query.value.kind)
);
const search = ref(query.value.q);
watch(
  () => query.value.q,
  (value) => {
    search.value = value;
  }
);
const returnPath = useState<string | undefined>(
  "library-return-path",
  () => undefined
);
watch(
  () => route.fullPath,
  (value) => {
    if (route.path === "/library") returnPath.value = value;
  },
  { immediate: true }
);
const { sourceFilter } = useSourceFilter();
function update(patch: Partial<LibraryQuery>, replace = false) {
  const next = { ...query.value, page: 1, ...patch };
  const target = { path: "/library", query: writeLibraryQuery(next) };
  return replace ? router.replace(target) : router.push(target);
}
function submitSearch() {
  if (search.value !== query.value.q) update({ q: search.value }, true);
}
watchDebounced(search, submitSearch, { debounce: 300, maxWait: 1000 });
function clearFilters() {
  search.value = "";
  update({ q: "", kind: "" });
}
const { findPaginated } = useAPI();
const {
  data: result,
  isFetching,
  suspense,
  refetch,
} = useQuery({
  queryKey: computed(() => ["library-catalogue", query.value]),
  placeholderData: keepPreviousData,
  queryFn: async () => {
    try {
      const page = await findPaginated({
        endpoint: API_ENDPOINTS.catalog,
        sources: [],
        pageNo: query.value.page,
        itemsPerPage: 25,
        sortByProperty: query.value.kind === "realm" ? "sequence" : "name",
        queryParams: {
          name__icontains: query.value.q,
          kind: query.value.kind,
          document__key__in:
            query.value.source === "all" ? "" : query.value.source,
        },
      });
      return { page, failed: false };
    } catch {
      // 将可重试失败一起传给首屏，避免服务端错误提示在客户端变回加载状态。
      // 不把网络错误对象、请求配置或内部堆栈放进网页数据。
      return { page: null, failed: true };
    }
  },
});
const data = computed(() => result.value?.page);
const error = computed(() => result.value?.failed);
onServerPrefetch(async () => {
  await suspense();
});
const lastPage = computed(() =>
  Math.max(1, Math.ceil((data.value?.count || 0) / 25))
);
</script>
