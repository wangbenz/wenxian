<template>
  <ReferenceEntry v-if="item?.document?.gamesystem?.key === 'xianxia'" :entry="item" :kind="item.category.content_kind" :kind-name="item.category.name">
    <section v-if="item.spells?.length" aria-label="可施展法术"><h2>可施展法术</h2><ul><li v-for="spell in item.spells" :key="spell.key"><NuxtLink :to="`/spells/${spell.key}`">{{ spell.name }}</NuxtLink></li></ul></section>
  </ReferenceEntry>
  <section v-else class="docs-container container">
    <div v-if="item">
      <LibraryBackLink />
      <h1 class="inline">
        {{ item.name }}
      </h1>
      <p>
        <em>
          {{ item.category.name }}<template v-if="item.rarity"> · {{ item.rarity.name }}</template>
          <span v-if="item.requires_attunement === true">
            ({{ '需要原规则同调' }})
          </span>
        </em>
        <SourceTag
          v-show="item.document.key"
          :title="item.document.name"
          :text="item.document.name"
        />
      </p>
      <MdViewer
        :text="item.desc"
        :crossreferences="item.crossreferences?.to"
      />
      <section v-if="item.spells?.length" class="my-4" aria-label="可施展法术">
        <h2>可施展法术</h2>
        <ul><li v-for="spell in item.spells" :key="spell.key">
          <NuxtLink :to="`/spells/${spell.key}`">{{ spell.name }}</NuxtLink>
        </li></ul>
      </section>
      <p class="text-sm italic">
        来源：
        <NuxtLink
          :to="`/sources/${item.document.key}`"
        >
          {{ item.document.name }}
          <Icon name="heroicons:arrow-top-right-on-square-20-solid" />
        </NuxtLink>
      </p>
    </div>
    <p v-else>
      正在读取资料…
    </p>
  </section>
</template>

<script setup lang="ts">
import type { MagicItem } from '@/types';

const itemId = useQueryParameter('id');
const { data: item } = useFindOne(API_ENDPOINTS.magicitems, itemId);

useSeoEntry(item as Ref<MagicItem>);
</script>
