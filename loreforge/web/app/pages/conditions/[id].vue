<template>
  <section
    v-if="condition"
    class="docs-container container"
  >
    <h1 class="m-b-4">
      <span>{{ condition.name }}</span>

    </h1>

    <p v-if="visibleDescriptions.length === 0" class="my-4 italic text-granite">
      尚未录入这个状态的说明。
    </p>

    <div v-else-if="visibleDescriptions.length === 1">
      <p class="text-sm text-granite">说明来源：<NuxtLink :to="`/sources/${visibleDescriptions[0].document}`">{{ documentName(visibleDescriptions[0].document) }}</NuxtLink> · {{ gameSystemName(visibleDescriptions[0].gamesystem) }}</p>
      <MdViewer :text="visibleDescriptions[0].desc" />
    </div>

    <template v-else-if="tabItems.length >= 2">
      <TabBar v-model="activeTab" :tabs="tabItems" class="my-4" />

      <div
        :id="`tabpanel-${activeTab}`"
        role="tabpanel"
        :aria-labelledby="`tab-${activeTab}`"
      >
        <template v-if="activeDescriptions.length === 1">
          <p class="text-sm text-granite">说明来源：<NuxtLink :to="`/sources/${activeDescriptions[0].document}`">{{ documentName(activeDescriptions[0].document) }}</NuxtLink></p>
          <MdViewer :text="activeDescriptions[0].desc" />
        </template>

        <div v-else class="grid gap-4">
          <div
            v-for="description in activeDescriptions"
            :key="description.document"
            class="rounded border border-fog p-4 dark:border-charcoal"
          >
            <SourceTag
              :text="description.document"
              :title="documentName(description.document)"
            />
            <MdViewer :text="description.desc" />
          </div>
        </div>
      </div>
    </template>

    <div v-else class="grid gap-4">
      <div
        v-for="description in visibleDescriptions"
        :key="description.document"
        class="rounded border border-fog p-4 dark:border-charcoal"
      >
        <SourceTag
          :text="description.document"
          :title="documentName(description.document)"
        />
        <MdViewer :text="description.desc" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { Condition, TabBarItem } from '@/types';

type ConditionDescription = Condition['descriptions'][number];

const conditionId = useQueryParameter('id');
const { data: condition } = useFindOne(API_ENDPOINTS.conditions, conditionId,
  {
    params: {
      fields: ['name', 'descriptions', 'document'].join(','),
      document__fields: ['key', 'display_name'].join(',')
    },
  },
);

useSeoEntry(condition as Ref<Condition>);

const { sources } = useSourcesList();
const { documentName, gameSystemName, sortGameSystemKeys } = useCatalog();

const visibleDescriptions = computed(() =>
  condition.value?.descriptions.filter(
    description => !sources.value.length || sources.value.includes(description.document),
  ) ?? [],
);

const descriptionsByGameSystem = computed(() => {
  const groups = new Map<string, ConditionDescription[]>();

  for (const description of visibleDescriptions.value) {
    const existing = groups.get(description.gamesystem) ?? [];
    groups.set(description.gamesystem, [...existing, description]);
  }

  return groups;
});

const tabItems = computed<TabBarItem[]>(() =>
  sortGameSystemKeys([...descriptionsByGameSystem.value.keys()]).map(key => ({
    id: key,
    label: gameSystemName(key),
  })),
);

const activeTab = ref('');

watch(tabItems, (tabs) => {
  if (tabs.length === 0) {
    activeTab.value = '';
    return;
  }
  if (!tabs.some(tab => tab.id === activeTab.value)) {
    activeTab.value = tabs[0]!.id;
  }
}, { immediate: true });

const activeDescriptions = computed(() =>
  descriptionsByGameSystem.value.get(activeTab.value) ?? [],
);

const sourceKey = computed(() => {
  if (!condition?.value?.document) return;
  return condition.value.document.key;
});
</script>
