<script setup lang="ts">
import AppSidebar from './AppSidebar.vue'
import AppHeader from './AppHeader.vue'
</script>

<template>
  <div class="flex h-screen overflow-hidden bg-surface-base">
    <AppSidebar />
    <div class="flex-1 grid min-w-0" style="grid-template-rows: 56px 1fr">
      <AppHeader />
      <main class="overflow-y-auto">
        <router-view />
      </main>
    </div>
  </div>
</template>
