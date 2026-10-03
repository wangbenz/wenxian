import { afterEach, describe, expect, test } from 'vitest';
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime';
import SpellPage from '~/pages/spells/[id].vue';
import MagicItemPage from '~/pages/magic-items/[id].vue';
import SourcePage from '~/pages/sources/[id].vue';

const source = {
  key: 'loreforge-pilot', name: '开物修仙试录', display_name: '开物修仙试录',
  publisher: { name: '开物' }, gamesystem: { key: 'cultivation', name: '修仙示范设定' },
};
let record: Record<string, unknown> = {};
let page: Awaited<ReturnType<typeof mountSuspended>> | undefined;
mockNuxtImport('useFindOne', () => () => ({ data: ref(record) }));
afterEach(() => { page?.unmount(); });

describe('cultivation reading without invented 5e values', () => {
  test('a spell with null school/level/time still renders its state reference', async () => {
    record = {
      name: '隐身术', key: 'loreforge_invisibility', document: source,
      level: null, school: null, casting_time: null, concentration: null, ritual: null,
      classes: [], range_text: 'Self', duration: 'up to 1 hour',
      verbal: true, somatic: true, material: false, higher_level: null,
      desc: '以诵咒、手势对自身施展。维持代价尚未确定。',
      applies_conditions: [{ key: 'loreforge_invisible', name: '隐身' }],
    };
    page = await mountSuspended(SpellPage);
    expect(page.find('h1').text()).toBe('隐身术');
    expect(page.text()).toContain('最长1小时');
    expect(page.text()).not.toMatch(/原规则戏法|null 环|维持专注|施法时间：/);
    expect(page.find('[aria-label="施加状态"] a').attributes('href')).toBe('/conditions/loreforge_invisible');
  });

  test('an item references the shared spell and does not invent a rarity or attunement', async () => {
    record = {
      name: '探灵杖', key: 'loreforge_detection-wand', document: source,
      category: { name: '杖' }, rarity: null, requires_attunement: null,
      cost: null, weight: null, desc: '最多储存3次探灵能力。',
      spells: [{ key: 'loreforge_detect-spiritual-effects', name: '探灵术' }],
    };
    page = await mountSuspended(MagicItemPage);
    expect(page.text()).toContain('最多储存3次');
    expect(page.text()).not.toMatch(/需要原规则同调|普通|0 gp/);
    expect(page.find('[aria-label="可施展法术"] a').attributes('href')).toBe('/spells/loreforge_detect-spiritual-effects');
  });

  test('an unpublished source does not invent a license, date or permalink', async () => {
    record = { ...source, desc: '本地改编试录。', licenses: [], publication_date: null, permalink: null };
    page = await mountSuspended(SourcePage);
    expect(page.text()).toContain('未另行声明发布许可');
    expect(page.text()).toContain('发布日期：未注明');
    expect(page.text()).not.toContain('查看原始资料');
  });
});
