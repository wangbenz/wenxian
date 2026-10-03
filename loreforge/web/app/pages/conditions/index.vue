<template>
  <section class="w-full">
    <div class="flex">
      <h1 class="my-2">状态</h1>
    </div>

    <ResultsTableFilter
      :filter-state="filterState"
      :search="{
        name: '搜索状态名称',
        filterField: 'name__contains',
      }"
      :select-fields="[sourceFilter]"
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
import {
  conditionsApiParams,
  conditionTableColumnDefinitions,
} from '@/helpers/resultsTableConfig';
import type { Condition } from '@/types';

useSeoIndex({ title: '状态' });

// Set up filters
const filterState = useFilterState<{ name__contains: string; source_document: string }>({
  key: 'conditions',
  fields: { name__contains: '', source_document: '' },
});
const { sourceFilter } = useSourceFilter('source_document');
const { documentName } = useCatalog();
const tableColumns = computed(() => conditionTableColumnDefinitions.map(column => ({
  ...column,
  sourceLabels: (condition: Condition) => [...new Set(condition.descriptions
    .filter(description => !filterState.fieldsState.value.source_document || description.document === filterState.fieldsState.value.source_document)
    .map(description => documentName(description.document)))],
})));

const { sortBy, isSortDescending, setSortState } = useSortState();

// fetch a page of data from API, and pagination controls
const { data, paginator } = useFindPaginated({
  endpoint: API_ENDPOINTS.conditions,
  sortByProperty: sortBy,
  isSortDescending: isSortDescending,
  filter: filterState.debouncedFilter,
  params: conditionsApiParams,
});

</script>
