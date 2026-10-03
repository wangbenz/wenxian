import { expect, test } from 'vitest';
import { spellTableColumnDefinitions, magicItemTableColumnDefinitions } from '~/helpers/resultsTableConfig';
import type { Spell, MagicItem } from '~/types';

test('a null spell level/school is not displayed as cantrip or an invented school', () => {
  const spell = { level: null, school: null } as Spell;
  expect(spellTableColumnDefinitions[1]!.value(spell)).toBe('—');
  expect(spellTableColumnDefinitions[2]!.value(spell)).toBe('—');
});

test('unknown attunement is distinguishable from an explicit false', () => {
  const column = magicItemTableColumnDefinitions[3]!;
  expect(column.value({ requires_attunement: null } as MagicItem)).toBe('未定义');
  expect(column.value({ requires_attunement: false } as MagicItem)).toBe(false);
});
