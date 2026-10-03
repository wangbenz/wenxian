<template>
  <section class="docs-container container">
    <div class="flex">
      <h1 class="my-2 w-full">
        法术
      </h1>
    </div>

    <ResultsTableFilter
      :filter-state="filterState"
      :search="{
        name: '搜索法术名称',
        filterField: 'name__contains',
      }"
      :select-fields="selectFields"
    />

    <!-- RESULTS TABLE -->
    <ResultsTable
      :data="data?.results"
      :is-loading="paginator.isFetching"
      :error="paginator.error"
      :cols="tableColumns"
      :sort-by="sortBy"
      :is-sort-descending="isSortDescending"
      @sort="(sortValue) => setSortState(sortValue)"
    />
    <ResultsTablePaginator
        :page-number="paginator.pageNo || 1"
        :last-page-number="paginator.lastPageNo || 1"
        :items-per-page="paginator.itemsPerPage || 1"
        :total-items="data?.count ?? 0"
        @first="paginator.firstPage()"
        @next="paginator.nextPage()"
        @prev="paginator.prevPage()"
        @last="paginator.lastPage()"
      />
  </section>
</template>

<script setup lang="ts">
import type { SpellFilterState } from '@/types';
import {
  spellsApiParams,
  spellFilterDefaults,
  spellFilterSelectFieldsDefinition,
  spellTableColumnDefinitions,
  cultivationSpellColumns,
} from '@/helpers/resultsTableConfig';

useSeoIndex({ title: '法术' });

// Set up filters
const filterState = useFilterState<SpellFilterState>({
  key: 'spells',
  fields: spellFilterDefaults,
});

const { sourceFilter, isCultivationSource } = useSourceFilter();
const isCultivation = computed(() => isCultivationSource(filterState.fieldsState.value.document__key__in));
const selectFields = computed(() => [sourceFilter.value, ...(isCultivation.value ? [] : spellFilterSelectFieldsDefinition)]);
const tableColumns = computed(() => isCultivation.value ? cultivationSpellColumns : spellTableColumnDefinitions);
watch(isCultivation, value => {
  if (value) filterState.setFilterFields({ level: undefined, school__key: undefined });
});

// State handlers for sorting results table
const { sortBy, isSortDescending, setSortState } = useSortState();

// Fetch a page of results and pagination controls
const { data, paginator } = useFindPaginated({
  endpoint: API_ENDPOINTS.spells,
  sortByProperty: sortBy,
  isSortDescending: isSortDescending,
  filter: filterState.debouncedFilter,
  params: spellsApiParams,
});

</script>
