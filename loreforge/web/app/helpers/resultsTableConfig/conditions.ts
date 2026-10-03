import type { TableColumn, Condition } from '@/types';

export const conditionsApiParams = {
  fields: ['name', 'key', 'document', 'descriptions'].join(','),
  document__fields: ['name', 'key'].join(','),
};

export const conditionTableColumnDefinitions: TableColumn<Condition>[] = [
  {
    displayName: '名称',
    value: (data) => data.name,
    link: (data) => `/conditions/${data.key}`,
  },
];
