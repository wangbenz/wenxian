import type { TableColumn, RuleSet } from '@/types';

export const rulesApiParams = {
  fields: ['name', 'key', 'document'].join(','),
  document__fields: ['name', 'key'].join(','),
};

export const rulesTableColumnDefinitions: TableColumn<RuleSet>[] = [
  {
    displayName: '名称',
    value: (data) => data.name,
    sortValue: 'name',
    link: (data) => `/rules/${data.key}`,
  }
];