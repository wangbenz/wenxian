<template>
  <main class="mx-auto max-w-xl p-8">
    <h1>
      {{ error?.statusCode === 404 ? "没有找到这份资料" : "资料暂时无法读取" }}
    </h1>
    <p>
      {{
        error?.statusCode === 404
          ? "这个栏目或条目不存在。"
          : "请检查资料服务连接后重试。"
      }}
    </p>
    <button
      class="rounded border border-smoke px-4 py-2 text-red"
      @click="clearError({ redirect: '/spells' })"
    >
      返回法术目录
    </button>
  </main>
</template>
<script setup lang="ts">
defineProps<{ error: { statusCode?: number } | null }>();
</script>
