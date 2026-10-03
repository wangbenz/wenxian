import type { TableColumn, Item } from "@/types";
import { equipmentCategories } from "@/constants";

export const equipmentApiParams = {
  fields: ["key", "name", "document", "category"].join(","),
  document__fields: ["name", "key"].join(","),
  category__fields: ["name"].join(","),
  is_magic_item: false,
};

export const equipmentTableColumnDefinitions: TableColumn<Item>[] = [
  {
    displayName: "名称",
    value: (data) => data.name,
    sortValue: "name",
    link: (data) => `/equipment/${data.key}`,
  },
  {
    displayName: "类别",
    value: (data) => data.category.name,
    sortValue: "category",
  },
];
export const equipmentFilterSelectFieldsDefinition = [
  {
    name: "类别",
    filterField: "category",
    options: equipmentCategories.map((category) => ({
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
        )[category] || category,
      value: category.toLowerCase().split(" ").join("-"),
    })),
  },
];
