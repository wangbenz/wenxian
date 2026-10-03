<template>
  <section class="w-full">
    <div class="flex">
      <h1 class="my-2">
        物品
      </h1>
    </div>

    <ResultsTableFilter
      :filter-state="filterState"
      :search="{
        name: '搜索物品名称',
        filterField: 'name__icontains',
      }"
      :select-fields="equipmentFilterSelectFieldsDefinition"
    />

    <ResultsTable
      :data="data?.results"
      :is-loading="paginator.isFetching"
      :error="paginator.error"
      :cols="equipmentTableColumnDefinitions"
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
  equipmentTableColumnDefinitions,
  equipmentFilterSelectFieldsDefinition,
  equipmentApiParams
} from '@/helpers/resultsTableConfig';

useSeoIndex({ title: '物品' });

// Set up filters
const filterState = useFilterState<{ name__icontains: string }>({
  key: 'equipment',
  fields: { name__icontains: '' },
});

// State handlers for sorting results table
const { sortBy, isSortDescending, setSortState } = useSortState();

const { data, paginator } = useFindPaginated({
  endpoint: API_ENDPOINTS.equipment,
  sortByProperty: sortBy,
  isSortDescending: isSortDescending,
  filter: filterState.debouncedFilter,
  params: equipmentApiParams,
});

</script>
