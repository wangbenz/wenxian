<template>
  <ReferenceEntry v-if="spell?.document?.gamesystem?.key === 'xianxia'" :entry="spell" kind="spell">
    <section v-if="spell.applies_conditions?.length" aria-label="施加状态"><h2>施加状态</h2><ul><li v-for="condition in spell.applies_conditions" :key="condition.key"><NuxtLink :to="`/conditions/${condition.key}`">{{ condition.name }}</NuxtLink></li></ul></section>
  </ReferenceEntry>
  <main v-else-if="spell">
    <LibraryBackLink />
    <h1>{{ spell.name }}</h1>
    <p  class="italic">
      <span v-if="spell.level === 0">
        原规则戏法<span v-if="spell.school"> · {{ spell.school.name }}</span>
      </span>
      <span v-else-if="spell.level != null">
        原规则 {{ spell.level }} 环<span v-if="spell.school"> · {{ spell.school.name }}</span>
      </span>
      <span v-if="spell.ritual"> （原规则仪式）</span>
      <span v-if="spell.classes.length > 0">
        {{ "（原规则职业：" + spell.classes.map((c) => c.name).join('、') + "）" }}
      </span>

      <SourceTag
        v-show="spell.document.key"
        :title="spell.document.name"
        :text="spell.document.name"
      />
    </p>

    <section class="my-4">
      <p v-if="spell.casting_time" class="my-0 grid grid-cols-[10rem,_1fr]">
        <span class="font-bold">施法时间： </span>
        <span>{{ formatCastingTime(spell) }}</span>
      </p>
      <p v-if="spell.range_text" class="my-0 grid grid-cols-[10rem,_1fr]">
        <span class="font-bold">距离： </span>
        <span>{{ spellRangeLabel(spell.range_text) }}</span>
      </p>
      <p v-if="spell.duration" class="my-0 grid grid-cols-[10rem,_1fr]">
        <span class="font-bold">持续时间： </span>
        <span class="capitalize">{{
          spell.concentration 
            ? `维持专注，${spellDurationLabel(spell.duration)}`
            : spellDurationLabel(spell.duration)
          }}
        </span>
      </p>
      <p v-if="[spell.verbal, spell.somatic, spell.material].some(value => value != null)" class="my-0 grid grid-cols-[10rem,_1fr]">
        <span class="font-bold">施法要素： </span>
        <span>
          <span>
            {{ formatComponents(spell.verbal, spell.somatic, spell.material) }}
          </span>
          <b v-if="spell.material_consumed">*</b>
          <span v-if="spell.material_specified" class="text-slate-600 dark:text-slate-300"
          >
            ({{ spell.material_specified }})
          </span>
        </span>
      </p>
    </section>

    <section v-if="spell.applies_conditions?.length" class="my-4" aria-label="施加状态">
      <h2>施加状态</h2>
      <ul><li v-for="condition in spell.applies_conditions" :key="condition.key">
        <NuxtLink :to="`/conditions/${condition.key}`">{{ condition.name }}</NuxtLink>
      </li></ul>
    </section>

    <section>
      <MdViewer
        :text="spell.desc"
        
        :crossreferences="spell?.crossreferences?.to"
      />
    </section>

    <section v-if="spell.higher_level" class="mt-4 ">
      <h2 class="inline border-b-0 text-base font-bold no-underline">
        {{ spell.level != null && spell.level > 0 ? "原规则升环效果：" : "原规则戏法成长：" }}
      </h2>
      <MdViewer
        :text="spell.higher_level"
        :inline="true"
        :crossreferences="spell?.crossreferences?.to"
      />
    </section>

    <p class="text-sm italic">
      <span>来源： </span>
      <NuxtLink :to="`/sources/${spell.document.key}`">
        {{ spell.document.name }} ·
        {{ spell.document.publisher.name || '出版者未注明' }}
        <Icon name="heroicons:arrow-top-right-on-square-20-solid" />
      </NuxtLink>
    </p>
  </main>
</template>

<script setup lang="ts">
import type { Spell } from '@/types';
import { spellRangeLabel, spellDurationLabel } from '@/helpers/spellLabels';
const spellId = useQueryParameter('id');
const { data: spell } = useFindOne(API_ENDPOINTS.spells, spellId);

useSeoEntry(spell as Ref<Spell>);

function formatCastingTime(spell: Spell) {
  const { casting_time, reaction_condition } = spell;
  if (!casting_time) return '';
  
  const formattedTitle = casting_time
    .split('-').join(' ') // rmv '-' from 'bonus-action'
    .replace(/(\d+)([a-zA-Z]+)/, '$1 $2') // insert space into '1hour', etc.
    .replace(/^./, casting_time[0].toUpperCase());
    
  if (reaction_condition) return `${formattedTitle}, ${reaction_condition}`;

  return formattedTitle;
}

function formatComponents(verbal?: boolean | null, somatic?: boolean | null, material?: boolean | null) {
  return [
    verbal && '言语',
    somatic && '动作',
    material && '材料'
  ].filter(Boolean).join('、') || '未列明要素，详见正文';
}
</script>
