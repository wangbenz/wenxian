export const spellLevels = [
  "Cantrip",
  "1st-level",
  "2nd-level",
  "3rd-level",
  "4th-level",
  "5th-level",
  "6th-level",
  "7th-level",
  "8th-level",
  "9th-level",
] as const;

export const spellSchools = [
  "Abjuration",
  "Conjuration",
  "Divination",
  "Enchantment",
  "Evocation",
  "Illusion",
  "Necromancy",
  "Transmutation",
] as const;

export const spellcastingClasses = [
  "Bard",
  "Cleric",
  "Druid",
  "Ranger",
  "Paladin",
  "Sorcerer",
  "Warlock",
  "Wizard",
] as const;

// Display names only: API school keys and source rules remain unchanged.
export const spellSchoolLabels: Record<string, string> = {
  abjuration: "防护",
  conjuration: "咒法",
  divination: "预言",
  enchantment: "惑控",
  evocation: "塑能",
  illusion: "幻术",
  necromancy: "死灵",
  transmutation: "变化",
};
