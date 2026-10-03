<template>
  <component
    :is="icon"
    aria-hidden="true"
    class="inline-block h-[1em] w-[1em]"
  />
</template>
<script setup lang="ts">
import { computed } from "vue";
import {
  MagnifyingGlassIcon,
  Bars3Icon,
  HomeIcon,
  XMarkIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronDoubleLeftIcon,
  ChevronDoubleRightIcon,
  ChevronUpIcon,
  ChevronDownIcon,
  ArrowTopRightOnSquareIcon,
  BookOpenIcon,
  SparklesIcon,
  CubeIcon,
  HeartIcon,
  DocumentTextIcon,
  ArrowsUpDownIcon,
} from "@heroicons/vue/24/outline";
const props = defineProps<{ name: string }>();
const icons: Record<string, any> = {
  search: MagnifyingGlassIcon,
  "search-line": MagnifyingGlassIcon,
  "menu-line": Bars3Icon,
  home: HomeIcon,
  "x-mark": XMarkIcon,
  "chevron-left": ChevronLeftIcon,
  "chevron-right": ChevronRightIcon,
  "chevron-double-left": ChevronDoubleLeftIcon,
  "chevron-double-right": ChevronDoubleRightIcon,
  "chevron-up": ChevronUpIcon,
  "chevron-down": ChevronDownIcon,
  "arrow-top-right-on-square-20-solid": ArrowTopRightOnSquareIcon,
  book: BookOpenIcon,
  sparkles: SparklesIcon,
  cube: CubeIcon,
  heart: HeartIcon,
  document: DocumentTextIcon,
  "arrows-up-down": ArrowsUpDownIcon,
};
const icon = computed(
  () => icons[props.name.split(":").pop()!] || BookOpenIcon
);
</script>
