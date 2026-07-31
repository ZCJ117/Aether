<script setup lang="ts">
import { AlertTriangle, CheckCircle2 } from 'lucide-vue-next'

defineProps<{
  errors: { nodeId?: string; message: string }[]
}>()
</script>

<template>
  <div class="space-y-2">
    <!-- No errors -->
    <div
      v-if="errors.length === 0"
      class="flex items-center gap-2 px-3 py-2 rounded-md bg-accent-green/5
             border border-accent-green/10"
    >
      <CheckCircle2 class="w-4 h-4 text-accent-green flex-shrink-0" />
      <span class="text-sm text-accent-green/80">工作流校验通过</span>
    </div>

    <!-- Error list -->
    <div v-else class="space-y-1.5">
      <div
        v-for="(err, index) in errors"
        :key="index"
        class="flex items-start gap-2 px-3 py-2 rounded-md bg-accent-red/5
               border border-accent-red/10"
      >
        <AlertTriangle class="w-4 h-4 text-accent-red flex-shrink-0 mt-0.5" />
        <div class="flex-1 min-w-0">
          <p class="text-sm text-accent-red/90 leading-relaxed">
            {{ err.message }}
          </p>
          <p
            v-if="err.nodeId"
            class="text-2xs text-secondary/50 mt-0.5 font-mono"
          >
            {{ err.nodeId }}
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
