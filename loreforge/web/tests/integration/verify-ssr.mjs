import assert from 'node:assert/strict';
import test from 'node:test';

// Nuxt's client test transform removes onServerPrefetch. Verify the real production
// HTML instead of pretending that createSSRApp in a client transform tests SSR.
const origin = new URL(process.env.LOREFORGE_PREVIEW_URL || 'http://127.0.0.1:5178');
assert.ok(['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname));

async function renderedBody(path) {
  const response = await fetch(new URL(path, origin), { signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, 200, `${path} must render successfully`);
  const html = await response.text();
  // Data present only inside the hydration payload must not satisfy these checks.
  const withoutPayload = html.replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, '');
  const body = withoutPayload.split(/<body\b[^>]*>/i)[1];
  assert.ok(body, 'Expected a rendered HTML body');
  assert.doesNotMatch(body, /<(?:RouterLink|NuxtLink)(?:\s|>)/i,
    'Links must resolve on the server; duplicate Vue runtimes used to leave empty custom tags');
  return body;
}

test('server HTML contains spell rows and working links before hydration', async () => {
  const body = await renderedBody('/spells');
  assert.match(body, /href="\/spells\/srd-2024_fire-bolt"/);
  assert.ok(body.includes('火弹术') && body.includes('疗伤术'));
});

test('spell detail is rendered on the server with its source link', async () => {
  const body = await renderedBody('/spells/srd-2024_fire-bolt');
  assert.ok(body.includes('火弹术') && body.includes('原英文正文'));
  assert.match(body, /href="\/sources\/srd-2024"/);
});

test('source names and licenses are rendered instead of only loading keys', async () => {
  const body = await renderedBody('/sources');
  assert.ok(body.includes('Wizards of the Coast'));
  assert.ok(body.includes('System Reference Document 5.2'));
  assert.ok(body.includes('Creative Commons Attribution 4.0'));
});

test('cultivation spells render nullable fields and an actual condition link', async () => {
  const body = await renderedBody('/spells/loreforge_invisibility');
  assert.ok(body.includes('隐身术') && body.includes('开物修仙试录'));
  assert.match(body, /href="\/conditions\/loreforge_invisible"/);
  assert.doesNotMatch(body, /原规则戏法|null 环/);
});

test('the cultivation item links to the shared spell definition', async () => {
  const body = await renderedBody('/magic-items/loreforge_detection-wand');
  assert.ok(body.includes('可施展法术') && body.includes('探灵杖'));
  assert.match(body, /href="\/spells\/loreforge_detect-spiritual-effects"/);
});

test('source filters and both versions are present in the first server render', async () => {
  const body = await renderedBody('/spells');
  assert.ok(body.includes('资料来源') && body.includes('开物修仙试录'));
  assert.match(body, /href="\/spells\/loreforge_invisibility"/);
  assert.match(body, /href="\/spells\/srd-2024_invisibility"/);
});

test('unified library renders imported content and category filters before hydration', async () => {
  const body = await renderedBody('/library');
  assert.ok(body.includes('共 107 条资料') && body.includes('资料分类'));
  assert.ok(body.includes('凡人流修仙资料') && body.includes('妖兽'));
  assert.match(body, /href="\/(?:rules\/entries|spells|equipment|magic-items)\/xianxia_/);
});

test('realm and beast entries render their complete text and source', async () => {
  for (const [key, name] of [['realm-foundation', '筑基'], ['creature-nine-tail-fox', '九尾狐']]) {
    const body = await renderedBody(`/rules/entries/xianxia_${key}`);
    assert.ok(body.includes(name));
    assert.match(body, /href="\/sources\/xianxia-core"/);
    assert.match(body, /href="\/library"/);
    assert.doesNotMatch(body, /href="\/rules\/entries"/);
  }
});

test('authored spell and material details do not fabricate 5e statistics', async () => {
  const spell = await renderedBody('/spells/xianxia_spell-fire-bolt');
  assert.ok(spell.includes('火弹术') && spell.includes('来源与改编'));
  assert.doesNotMatch(spell, /原规则戏法|null 环/);
  const material = await renderedBody('/equipment/xianxia_resource-spirit-stone');
  assert.ok(material.includes('灵石'));
  assert.doesNotMatch(material, /null 磅|0 磅/);
});

test('category and name URLs are directly readable without client-side filtering', async () => {
  const realms = await renderedBody('/library?kind=realm');
  assert.ok(realms.includes('共 8 条资料'));
  assert.match(realms, /href="\/rules\/entries\/xianxia_realm-foundation"/);
  assert.doesNotMatch(realms, /href="\/magic-items\/xianxia_pill-foundation"/);
  const pill = await renderedBody('/library?kind=pill&q=' + encodeURIComponent('筑基丹'));
  assert.ok(pill.includes('共 1 条资料'));
  assert.match(pill, /href="\/magic-items\/xianxia_pill-foundation"/);
});

test('catalogue page and reading sections have real server-side content', async () => {
  const page = await renderedBody('/library?page=2');
  assert.ok(page.includes('第 2 / 5 页'));
  assert.equal((page.match(/class="catalogue-entry"/g) || []).length, 25);
  const detail = await renderedBody('/rules/entries/xianxia_realm-foundation');
  assert.ok(detail.includes('本页目录') && detail.includes('常规准备'));
  assert.match(detail, /href="#reading-section-2"/);
  assert.match(detail, /id="reading-section-2"/);
  assert.match(detail, /href="\/library\?kind=realm"/);
});

test('invalid pages retain the catalogue and offer a recoverable next action', async () => {
  const page = await renderedBody('/library?kind=realm&page=9999');
  assert.ok(page.includes('暂时无法读取这页资料') && page.includes('回到第一页'));
  assert.ok(page.includes('资料分类'));
  assert.doesNotMatch(page, /AxiosError|Request failed with status code/);
});

test('realm route follows progression and the high-realm path has working content links', async () => {
  const body = await renderedBody('/library?kind=realm');
  const ids = ['qi-refining', 'foundation', 'core-formation', 'nascent-soul', 'spirit-transformation', 'void-refinement', 'body-integration', 'mahayana'];
  const positions = ids.map(id => body.indexOf(`href="/rules/entries/xianxia_realm-${id}"`));
  assert.ok(positions.every((position, i) => position >= 0 && (!i || position > positions[i - 1])));
  const core = await renderedBody('/rules/entries/xianxia_realm-core-formation');
  assert.ok(core.includes('修习方式') && core.includes('突破准备') && core.includes('出处依据') && core.includes('本库改编'));
  assert.match(core, /href="\/rules\/entries\/xianxia_item-class-magic-treasure"/);
  assert.match(core, /href="\/rules\/entries\/xianxia_realm-nascent-soul"/);
  const last = await renderedBody('/rules/entries/xianxia_realm-mahayana');
  assert.match(last, /href="\/rules\/entries\/xianxia_process-tribulation-ascension"/);
  const process = await renderedBody('/rules/entries/xianxia_process-tribulation-ascension');
  assert.ok(process.includes('不作为第九个') && process.includes('过程与结果'));
});
