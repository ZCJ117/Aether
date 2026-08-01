<script setup lang="ts">
</script>

<template>
  <div class="flex h-full">
    <aside
      class="w-[220px] flex-shrink-0 flex flex-col border-r overflow-hidden"
      style="background: rgba(28,28,30,0.6); border-color: rgba(255,255,255,0.06);"
    >
      <slot name="sidebar" />
    </aside>

    <div class="flex-1 flex flex-col min-w-0">
      <div
        class="h-11 flex-shrink-0 flex items-center px-4 border-b"
        style="background: rgba(28,28,30,0.5); border-color: rgba(255,255,255,0.06);"
      >
        <slot name="header" />
      </div>
      <div class="flex-1 overflow-y-auto">
        <slot />
      </div>
      <div class="flex-shrink-0">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>
