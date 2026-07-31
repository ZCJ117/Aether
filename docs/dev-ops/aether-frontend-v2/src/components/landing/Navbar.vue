<script setup lang="ts">
import { LayoutDashboard, Sparkles } from 'lucide-vue-next'
</script>

<template>
  <nav
    class="fixed top-0 left-0 right-0 z-50 border-b border-white/5
           bg-surface-ink/70 backdrop-blur-xl"
  >
    <div class="max-w-7xl mx-auto px-6 h-14 flex items-center justify-between">
      <!-- Logo -->
      <router-link to="/" class="flex items-center gap-2 group">
        <div
          class="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-accent-blue
                 flex items-center justify-center"
        >
          <Sparkles class="w-4 h-4 text-surface-ink" />
        </div>
        <span class="text-sm font-bold text-primary tracking-tight">Aether</span>
      </router-link>

      <!-- Navigation -->
      <div class="flex items-center gap-3">
        <router-link
          to="/app/dashboard"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
                 text-secondary hover:text-primary hover:bg-white/5 transition-colors"
        >
          <LayoutDashboard class="w-3.5 h-3.5" />
          仪表盘
        </router-link>
        <router-link
          to="/login"
          class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
                 bg-primary/10 text-primary hover:bg-primary/20 border border-primary/10
                 transition-all"
        >
          登录
        </router-link>
      </div>
    </div>
  </nav>
</template>
