<template>
  <section class="docs-container container">
    <div class="flex w-full">
      <h1 class="my-2 w-full">
        魔法物品
      </h1>
    </div>

    <ResultsTableFilter
      :filter-state="filterState"
      :search="{
        name: '搜索魔法物品名称',
        filterField: 'name__icontains',
      }"
      :select-fields="selectFields"
      :checkbox-fields="isCultivation ? [] : filterCheckboxFieldsDefinition"
    />

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
import type { MagicItemFilterState } from '@/types';
import {
  magicItemApiParams,
  magicItemFilterDefaults,
  magicItemFilterSelectFieldsDefinition,
  magicItemTableColumnDefinitions,
} from '@/helpers/resultsTableConfig';

useSeoIndex({ title: '魔法物品' });

// Set up filters
const filterState = useFilterState<MagicItemFilterState>({
  key: 'magicItems',
  fields: magicItemFilterDefaults,
});

const { sourceFilter, isCultivationSource } = useSourceFilter();
const isCultivation = computed(() => isCultivationSource(filterState.fieldsState.value.document__key__in));
const selectFields = computed(() => [sourceFilter.value, ...(isCultivation.value ? [] : magicItemFilterSelectFieldsDefinition)]);
const tableColumns = computed(() => isCultivation.value ? magicItemTableColumnDefinitions.slice(0, 2) : magicItemTableColumnDefinitions);
watch(isCultivation, value => {
  if (value) filterState.setFilterFields({ rarity: '', category: '', requires_attunement: undefined });
});

const filterCheckboxFieldsDefinition = [{
  name: '原规则同调',
  filterField: 'requires_attunement',
}];

// State handlers for sorting results table
const { sortBy, isSortDescending, setSortState } = useSortState();

// fetch page of data from API and pagination controls
const { data, paginator } = useFindPaginated({
  endpoint: API_ENDPOINTS.magicitems,
  sortByProperty: sortBy,
  isSortDescending: isSortDescending,
  filter: filterState.debouncedFilter,
  params: magicItemApiParams
});
</script>
