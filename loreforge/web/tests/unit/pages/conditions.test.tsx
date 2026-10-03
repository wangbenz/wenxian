import { test, expect } from 'vitest';
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime';
import ConditionsPage from '~/pages/conditions/index.vue';

mockNuxtImport('useFindPaginated', () => () => ({
  data: ref({ count: 1, results: [{
    key: 'invisible', name: '隐身',
    document: { key: 'core', name: '5e Core Concepts' },
    descriptions: [{ document: 'srd-2024', gamesystem: '5e-2024', desc: '原状态说明' }],
  }] }),
  paginator: { pageNo: 1, lastPageNo: 1, firstPage() {}, lastPage() {}, prevPage() {}, nextPage() {} },
}));
mockNuxtImport('useSourceFilter', () => () => ({
  sourceFilter: ref({ name: '说明来源', filterField: 'source_document', options: [] }),
  isCultivationSource: () => false,
}));
mockNuxtImport('useCatalog', () => () => ({
  documentName: (key: string) => key === 'srd-2024' ? 'System Reference Document 5.2' : key,
}));

test('condition rows label the definition source rather than the concept identity source', async () => {
  const page = await mountSuspended(ConditionsPage);
  expect(page.find('h1').text()).toBe('状态');
  expect(page.find('tbody').text()).toContain('System Reference Document 5.2');
  expect(page.find('tbody').text()).not.toContain('5e Core Concepts');
  page.unmount();
});
