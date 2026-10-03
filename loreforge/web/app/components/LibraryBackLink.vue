<template>
  <p class="library-back">
    <NuxtLink :to="returnPath || fallback || '/library'"
      ><Icon name="chevron-left" /> 返回资料目录</NuxtLink
    >
  </p>
</template>
<script setup lang="ts">
defineProps<{ fallback?: string }>();
const returnPath = useState<string | undefined>(
  "library-return-path",
  () => undefined
);
</script>
