import type {
  MagicItem,
  MagicItemFilterState,
  ResultTableSelectFieldFilter,
  TableColumn,
} from "@/types";

import { magicItemRarities, magicItemTypes } from "@/constants";

// -- API --
export const magicItemApiParams = {
  fields: [
    "key",
    "name",
    "document",
    "category",
    "rarity",
    "requires_attunement",
  ].join(","),
  is_magic_item: true,
  document__fields: ["name", "key"].join(","),
  category__fields: ["name", "key"].join(","),
  rarity__fields: ["name", "rank"].join(","),
};

// -- TABLE --
export const magicItemTableColumnDefinitions: TableColumn<MagicItem>[] = [
  {
    displayName: "名称",
    value: (data) => data.name,
    sortValue: "name",
    link: (data) => `/magic-items/${data.key}`,
  },
  {
    displayName: "类别",
    value: (data) => data.category.name,
    sortValue: "category",
    isLeastPriority: true,
  },
  {
    displayName: "原稀有度",
    value: (data) => data.rarity?.name ?? "未注明",
    sortValue: "rarity",
  },
  {
    displayName: "原规则同调",
    value: (data) => data.requires_attunement ?? '未定义',
    sortValue: "requires_attunement",
  },
];

// -- FILTERS --
export const magicItemFilterSelectFieldsDefinition: ResultTableSelectFieldFilter[] =
  [
    {
      name: "原稀有度",
      filterField: "rarity",
      options: magicItemRarities.map((rarity) => ({
        name:
          (
            {
              Common: "普通",
              Uncommon: "非普通",
              Rare: "珍稀",
              "Very Rare": "极珍稀",
              Legendary: "传说",
              Armor: "护甲",
              Potion: "药水",
              Ring: "戒指",
              Rod: "权杖",
              Scroll: "卷轴",
              Staff: "法杖",
              Wand: "魔杖",
              Weapon: "武器",
              "Wondrous Item": "奇物",
              "Adventuring Gear": "冒险装备",
              Ammunition: "弹药",
              "Drawn Vehicle": "牵引载具",
              Poison: "毒物",
              Shield: "盾牌",
              Tools: "工具",
              "Trade Good": "贸易品",
              "Waterborne Vehicle": "水上载具",
            } as Record<string, string>
          )[rarity] || rarity,
        value: rarity.toLowerCase().split(" ").join("-"),
      })),
    },
    {
      name: "类别",
      filterField: "category",
      options: magicItemTypes.map((type) => ({
        name:
          (
            {
              Common: "普通",
              Uncommon: "非普通",
              Rare: "珍稀",
              "Very Rare": "极珍稀",
              Legendary: "传说",
              Armor: "护甲",
              Potion: "药水",
              Ring: "戒指",
              Rod: "权杖",
              Scroll: "卷轴",
              Staff: "法杖",
              Wand: "魔杖",
              Weapon: "武器",
              "Wondrous Item": "奇物",
              "Adventuring Gear": "冒险装备",
              Ammunition: "弹药",
              "Drawn Vehicle": "牵引载具",
              Poison: "毒物",
              Shield: "盾牌",
              Tools: "工具",
              "Trade Good": "贸易品",
              "Waterborne Vehicle": "水上载具",
            } as Record<string, string>
          )[type] || type,
        value: type.toLowerCase().split(" ").join("-"),
      })),
      isLeastPriority: true,
    },
  ];

export const magicItemFilterDefaults: Readonly<MagicItemFilterState> = {
  document__key__in: '',
  name__icontains: "",
  rarity: "",
  category: "",
  requires_attunement: undefined,
};
