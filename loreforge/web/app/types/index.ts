import type { components } from './open5e-api';
export * from './filters';

// export basic data types returned by Open5e API
export type Background = components['schemas']['Background'];
export type BackgroundBenefit = components['schemas']['BackgroundBenefit'];
export type Class = components['schemas']['CharacterClass'];
export type ClassSummary = components['schemas']['CharacterClassSummary'];
export type ClassFeature = components['schemas']['ClassFeature'];
export type Condition = Omit<components['schemas']['Condition'], 'document'> & { document: DocumentSummary };
export type Creature = components['schemas']['Creature'];
export type CreatureAction = components['schemas']['CreatureAction'];
export type CreatureTypeSummary = components['schemas']['CreatureTypeSummary'];
export type CrossReferenceLink = components['schemas']['CrossReferenceLink'];
export type Document = Omit<components['schemas']['Document'], 'publication_date' | 'permalink'> & {
  publication_date: string | null;
  permalink: string | null;
};
export type DocumentSummary = Omit<components['schemas']['DocumentSummary'], 'permalink'> & {
  permalink: string | null;
};
export type Feat = components['schemas']['Feat'];
export type GameSystem = components['schemas']['GameSystem'];
export type License = components['schemas']['License'];
export type Rule = RuleEntry;
export type RuleSet = Omit<components['schemas']['RuleSet'], 'document' | 'rules'> & {
  document: DocumentSummary; content_kind: string; rules: RuleEntry[];
};
export type RuleEntry = Omit<components['schemas']['Rule'], 'document'> & {
  key: string;
  document: DocumentSummary;
  content_kind: string;
  ruleset_name: string;
  reading_neighbors: ReadingNeighbors | null;
};
export type ReadingNeighbors = { previous: ContentReference | null; next: ContentReference | null };
export type CatalogEntry = {
  key: string; name: string; kind: string; kind_label: string; resource: string; excerpt: string; sequence: number | null;
  document: { key: string; name: string }; page_url: string; api_url: string;
};
export type CatalogPage = { count: number; next: string | null; previous: string | null; results: CatalogEntry[] };
export type SearchResult = components['schemas']['SearchResult'] & { object?: SearchObjectPayload };
export type Size = components['schemas']['Size'];
export type Species = components['schemas']['Species'];
// 开物对上游结构的最小扩展；null表示未定义或不适用，不转换为0/false。
export type ContentReference = { key: string; name: string };
type NullableFields<T, K extends keyof T> = Omit<T, K> & { [P in K]: T[P] | null };
export type Spell = NullableFields<Omit<components['schemas']['Spell'], 'document'>,
  'level' | 'school' | 'casting_time' | 'attack_roll' | 'concentration' |
  'ritual' | 'higher_level' | 'saving_throw_ability' | 'damage_roll' |
  'range_text' | 'duration' | 'verbal' | 'somatic' | 'material' | 'material_specified' | 'material_consumed'> & {
  applies_conditions: ContentReference[];
  document: DocumentSummary;
};
export type MagicItem = NullableFields<Omit<components['schemas']['MagicItem'], 'document' | 'category'>,
  'size' | 'weight' | 'rarity' | 'cost' | 'requires_attunement'> & {
    spells: ContentReference[];
    document: DocumentSummary;
    category: ItemCategorySummary;
  };
export type Monster = components['schemas']['Creature'];
export type Item = NullableFields<Omit<components['schemas']['Item'], 'document' | 'category'>, 'size' | 'weight'> & { document: DocumentSummary; category: ItemCategorySummary };
export type ItemCategory = components ['schemas']['ItemCategory'];
export type ItemCategorySummary = components['schemas']['ItemCategorySummary'] & { content_kind?: string };
export type Weapon = components['schemas']['Weapon'];
export type WeaponSummary = components['schemas']['WeaponSummary'];


// a union type of all possible data types returned by Open5e API
export type Open5eData = { 
  name: string;
  key: string;
  document?: DocumentSummary;
};


// the `object` field of the SearchResult type returns irregularly typed data
export type SearchObjectPayload = {
  cr?: string;
  type?: string;
  size?: string;
  subspecies_of?: {
    name: string;
    key: string;
  };
  subclass_of?: {
    name: string;
    key: string;
  }
  rarity?: string;
  is_magic_item?: boolean;
  school?: string;
  level?: number;
}

// type interface for the `cols` prop
export interface TabBarItem {
  id: string;
  label: string;
  subtitle?: string;
}

export interface TableColumn<T extends Open5eData> {
  displayName: string;
  value: (data: T) => string | number | boolean;
  sortValue?: string;
  link?: (data: T) => string;
  isLeastPriority?: boolean;
  customTemplate?: (data: T) => { render: () => VNode };
  sourceLabels?: (data: T) => string[];
};
