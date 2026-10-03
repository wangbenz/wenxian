<template>
  <section class="w-full">
    <div class="flex">
      <h1 class="my-2">规则</h1>
    </div>

    <ResultsTableFilter
      :filter-state="filterState"
      :search="{
        name: '搜索规则名称',
        filterField: 'name__contains',
      }"
    />

    <ResultsTable
      :data="data?.results"
      :is-loading="paginator.isFetching"
      :error="paginator.error"
      :cols="rulesTableColumnDefinitions"
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
  rulesApiParams,
  rulesTableColumnDefinitions,
} from '@/helpers/resultsTableConfig';

useSeoIndex({ title: '规则' });

// Set up filters
const filterState = useFilterState<{ name__contains: string }>({
  key: 'rules',
  fields: { name__contains: '' },
});

const { sortBy, isSortDescending, setSortState } = useSortState();

// fetch a page of data from API, and pagination controls
const { data, paginator } = useFindPaginated({
  endpoint: API_ENDPOINTS.rules,
  sortByProperty: sortBy,
  isSortDescending: isSortDescending,
  filter: filterState.debouncedFilter,
  params: rulesApiParams,
});

</script>
