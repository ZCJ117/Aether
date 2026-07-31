<script setup lang="ts">
// ============================================
// ChatLayout — Three-part flex layout
// ============================================
</script>

<template>
  <div class="flex h-full bg-[#0a0a0a] text-[#DEDBC8]">
    <!-- Left sidebar slot -->
    <aside class="w-64 flex-shrink-0 border-r border-white/5 bg-[#0f0f0f]">
      <slot name="sidebar" />
    </aside>

    <!-- Right area: header + content -->
    <div class="flex flex-1 flex-col min-w-0">
      <!-- Header slot -->
      <header class="flex-shrink-0 border-b border-white/5 bg-[#0f0f0f] px-4 py-3">
        <slot name="header" />
      </header>

      <!-- Content slot -->
      <main class="flex-1 min-h-0 flex flex-col">
        <slot />
      </main>
    </div>
  </div>
</template>
