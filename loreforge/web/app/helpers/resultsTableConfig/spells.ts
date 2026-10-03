import type {
  ResultTableSelectFieldFilter,
  Spell,
  SpellFilterState,
  TableColumn,
} from "@/types";

import { spellSchools, spellSchoolLabels } from "@/constants";
import { spellRangeLabel, spellDurationLabel } from '@/helpers/spellLabels';

// -- API --
export const spellsApiParams = {
  fields: ["key", "name", "document", "level", "school", "classes", "range_text", "duration"].join(","),
  document__fields: ["name", "key"].join(","),
  classes__fields: ["name"].join(","),
  school__fields: ["name", "key"].join(","),
};

// -- Table --
export const spellTableColumnDefinitions: TableColumn<Spell>[] = [
  {
    displayName: "名称",
    value: (data) => data.name,
    sortValue: "name",
    link: (data) => `/spells/${data.key}`,
  },
  {
    displayName: "原环阶",
    value: (data) => data.level ?? '—',
    sortValue: "level",
  },
  {
    displayName: "原学派",
    value: (data) => data.school ? (
      spellSchoolLabels[data.school.key] ||
      spellSchoolLabels[data.school.name.toLowerCase()] ||
      data.school.name) : '—',
    sortValue: "school",
  },
];

export const cultivationSpellColumns: TableColumn<Spell>[] = [
  spellTableColumnDefinitions[0]!,
  { displayName: '作用范围', value: data => spellRangeLabel(data.range_text) },
  { displayName: '持续时间', value: data => spellDurationLabel(data.duration) },
];

// -- Filters --
export const spellFilterSelectFieldsDefinition: ResultTableSelectFieldFilter[] =
  [
    {
      name: "原环阶",
      filterField: "level",
      options: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].map((level) => ({
        name: level.toString(),
        value: level.toString(),
      })),
    },
    {
      name: "原学派",
      filterField: "school__key",
      options: spellSchools.map((school) => ({
        name: spellSchoolLabels[school.toLowerCase()] || school,
        value: school.toLowerCase(),
      })),
    },
  ];

export const spellFilterDefaults: Readonly<SpellFilterState> = {
  document__key__in: '',
  name__contains: "",
  level: undefined,
  school__key: undefined,
  classes__name__in: undefined,
};
