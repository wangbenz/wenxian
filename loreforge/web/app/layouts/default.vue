<template>
  <div class="library-shell">
    <a class="skip-to-content" href="#main-content">跳到正文</a>
    <aside class="desktop-navigation"><Navigation /></aside>
    <div class="library-surface">
      <header class="mobile-library-header">
        <NuxtLink to="/library">开物<span>修仙资料库</span></NuxtLink
        ><button
          type="button"
          aria-label="打开资料目录"
          :aria-expanded="isNavbarVisible"
          @click="isNavbarVisible = true"
        >
          <Icon name="menu-line" /> 目录
        </button>
      </header>
      <main id="main-content" class="library-main" tabindex="-1">
        <div v-if="showBreadcrumbs" class="library-breadcrumbs">
          <BreadcrumbLinks />
        </div>
        <NuxtPage class="main-content min-w-0" />
      </main>
      <AppFooter />
    </div>
    <Dialog
      :open="isNavbarVisible"
      class="mobile-navigation-dialog"
      @close="isNavbarVisible = false"
    >
      <div class="navigation-backdrop" aria-hidden="true" />
      <DialogPanel class="mobile-navigation-panel"
        ><DialogTitle class="sr-only">修仙资料目录</DialogTitle
        ><button
          type="button"
          class="navigation-close"
          aria-label="关闭资料目录"
          @click="isNavbarVisible = false"
        >
          <Icon name="x-mark" /></button
        ><Navigation @on-link-clicked="isNavbarVisible = false"
      /></DialogPanel>
    </Dialog>
  </div>
</template>
<script setup lang="ts">
import { Dialog, DialogPanel, DialogTitle } from "@headlessui/vue";
const isNavbarVisible = ref(false);
const route = useRoute();
// 凡人流条目使用自己的分类与返回入口，避免继承资源表名称作为阅读层级。
const showBreadcrumbs = computed(
  () =>
    route.path !== "/library" &&
    !route.path.split("/").some((part) => part.startsWith("xianxia_"))
);
</script>
