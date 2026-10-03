<template>
  <article v-if="document" class="docs-container container">
    <div class="flex flex-wrap items-baseline gap-2">
      <h1>
        <span>{{ document.display_name || document.name }}</span>
        <SourceTag :text="document.key" />
      </h1>
      <p class="ml-2">
        <span>出版者：</span>
        <span class="font-bold">{{ document.publisher.name }}</span>
      </p>
      
    </div>

    <MdViewer :text="document.desc"/>

    <!-- Licensing information -->
    <p>
      <span>许可：</span>
      <span 
        v-for="(license, index) in document.licenses"
        :key="license.name"
      >
        <template v-if="index > 0"> / </template>
        {{ `${license.name} (${license.key.toUpperCase()})` }}
      </span>
      <span v-if="document.licenses.length === 0">本资料未另行声明发布许可；所引原资料的许可见上方出处。</span>
    </p>

    <p v-if="!document.publication_date">发布日期：未注明</p>

    <a
      v-if="document.permalink"
      :href="document.permalink"
      class="text-lg font-bold"
    >
      查看原始资料
    </a>
  </article>
</template>

<script setup lang="ts">
import type { Document } from '@/types';

const documentId = useQueryParameter('id');
const { data: document } = useFindOne(API_ENDPOINTS.documents, documentId);

useSeoEntry(document as Ref<Document>);

</script>
