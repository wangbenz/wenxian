<template>
  <section
    v-if="ruleset"
    class="docs-container container"
  >
    <LibraryBackLink />
    <h1>{{ ruleset.name }}</h1>
    <md-viewer :text="ruleset.desc" />
    <div
      v-for="rule in ruleset.rules"
      :key="rule.name"
    >
      <h2 :id="rule.key"><NuxtLink :to="`/rules/entries/${rule.key}`">{{ rule.name }}</NuxtLink></h2>
      <md-viewer :text="rule.desc" />
    </div>
  </section>
</template>

<script setup lang="ts">
import type { RuleSet } from '@/types';
const ruleId = useQueryParameter('id');
const { data: ruleset } = useFindOne(API_ENDPOINTS.rules, ruleId);

useSeoEntry(ruleset as Ref<RuleSet>);
</script>
