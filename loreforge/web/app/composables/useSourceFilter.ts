import type { ResultTableSelectFieldFilter } from '@/types';

/** 来源选项来自同一资料服务；不默认把同名的不同设定合并。 */
export function useSourceFilter(field = 'document__key__in') {
  const { documents } = useCatalog();
  const sourceFilter = computed<ResultTableSelectFieldFilter>(() => ({
    name: field === 'source_document' ? '说明来源' : '资料来源',
    filterField: field,
    options: documents.value.map(document => ({
      name: document.name,
      value: document.key,
    })),
  }));
  const isCultivationSource = (key?: string | boolean) =>
    documents.value.some(document => document.key === key && ['cultivation', 'xianxia'].includes(document.gamesystem.key));
  return { sourceFilter, isCultivationSource };
}
