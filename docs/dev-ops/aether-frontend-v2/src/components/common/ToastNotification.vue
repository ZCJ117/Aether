<script setup lang="ts">
import { useToast } from '@/composables/useToast'
import { CheckCircle, XCircle, AlertTriangle, Info, X } from 'lucide-vue-next'
import { computed, type Component } from 'vue'

const { toasts, remove } = useToast()

const iconMap: Record<string, Component> = {
  success: CheckCircle,
  error: XCircle,
  warning: AlertTriangle,
  info: Info,
}

const colorMap: Record<string, string> = {
  success: 'border-accent-green bg-accent-green',
  error: 'border-accent-red bg-accent-red',
  warning: 'border-accent-yellow bg-accent-yellow',
  info: 'border-accent-blue bg-accent-blue',
}

function getIcon(type: string): Component {
  return iconMap[type] ?? Info
}

function getColorBar(type: string): string {
  return colorMap[type] ?? colorMap.info
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed top-4 right-4 z-50 flex flex-col gap-3 pointer-events-none">
      <TransitionGroup name="toast-slide">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="pointer-events-auto flex items-start bg-surface-card border border-white/5 rounded-lg shadow-xl min-w-[320px] max-w-[420px] overflow-hidden"
        >
          <!-- Left color bar -->
          <div :class="getColorBar(toast.type)" class="w-1 self-stretch flex-shrink-0" />

          <!-- Icon -->
          <component
            :is="getIcon(toast.type)"
            :class="`text-accent-${toast.type}`"
            class="w-4 h-4 ml-3 mt-3 flex-shrink-0"
          />

          <!-- Message -->
          <p class="flex-1 text-sm text-primary py-3 px-2">{{ toast.message }}</p>

          <!-- Close button -->
          <button
            @click="remove(toast.id)"
            class="p-2 mr-1 mt-2 text-secondary hover:text-primary transition-colors flex-shrink-0"
          >
            <X class="w-3.5 h-3.5" />
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-slide-enter-active {
  transition: all 0.3s ease-out;
}
.toast-slide-leave-active {
  transition: all 0.2s ease-in;
}
.toast-slide-enter-from {
  opacity: 0;
  transform: translateX(100%);
}
.toast-slide-leave-to {
  opacity: 0;
  transform: translateX(100%);
}
</style>
