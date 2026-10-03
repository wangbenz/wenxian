<template>
  <section class="docs-container container">
    <h1>资料来源</h1>
    <p v-if="isLoading">正在读取资料…</p>
    <p v-else-if="!documents.length">尚未导入资料来源。</p>
    <ul>
      <li
        v-for="(docs, publisher) in sortDocumentsByPublisher(documents)"
        :key="publisher"
      >
        <h2 class="break-words">{{ publisher }}</h2>
        <dl class="flex flex-col">
          <div
            v-for="doc in docs"
            :key="doc.key"
            class="my-2 rounded border border-smoke bg-fog p-4"
          >
            <dt class="text-lg font-bold">
              <NuxtLink :to="`/sources/${doc.key}`">{{ doc.name }}</NuxtLink
              ><SourceTag :text="doc.key" />
            </dt>
            <dd>
              <p>{{ doc.desc?.split(/\n\s*\n/)[0] }}</p>
              <p v-if="doc.licenses.length" class="text-sm text-granite">
                许可：{{ doc.licenses.map((l) => l.name).join(" / ") }}
              </p>
            </dd>
          </div>
        </dl>
      </li>
    </ul>
  </section>
</template>
<script setup lang="ts">
import { sortDocumentsByPublisher } from "@/helpers";
const { documents, isLoading } = useCatalog();
useSeoIndex({ title: "资料来源" });
</script>
