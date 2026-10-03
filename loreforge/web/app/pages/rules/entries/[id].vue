<template>
  <ReferenceEntry v-if="entry?.document?.gamesystem?.key === 'xianxia'" :entry="entry" :kind="entry.content_kind" :kind-name="entry.ruleset_name" />
  <section v-else-if="entry" class="docs-container container">
    <LibraryBackLink />
    <h1>{{ entry.name }}</h1>
    <p class="text-sm text-granite">{{ entry.ruleset_name }}</p>
    <MdViewer :text="entry.desc" />
    <p class="text-sm">
      来源：<NuxtLink :to="`/sources/${entry.document.key}`">{{
        entry.document.name
      }}</NuxtLink>
    </p>
    <p v-if="entry.ruleset" class="text-sm">
      <NuxtLink :to="`/rules/${entry.ruleset}`">查看同组资料</NuxtLink>
    </p>
  </section>
</template>
<script setup lang="ts">
import type { RuleEntry } from "@/types";
const id = useQueryParameter("id");
const { data: entry } = useFindOne(API_ENDPOINTS.ruleEntries, id);
useSeoEntry(entry as Ref<RuleEntry>);
</script>
