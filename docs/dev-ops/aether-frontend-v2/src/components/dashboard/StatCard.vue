<script setup lang="ts">
import { computed } from 'vue'
import { TrendingUp, TrendingDown, Minus } from 'lucide-vue-next'
import type { Component } from 'vue'
import { formatNumber } from '@/utils/format'
import { cn } from '@/utils/cn'

const props = withDefaults(defineProps<{
  title: string
  value: number | string
  unit?: string
  trend?: number
  icon: Component
  loading?: boolean
  color?: string
}>(), {
  unit: '',
  trend: 0,
  loading: false,
  color: 'bg-blue-500',
})

const formattedValue = computed(() => {
  if (typeof props.value === 'number') return formatNumber(props.value)
  return props.value
})

const trendIcon = computed(() => {
  if (props.trend === 0) return Minus
  return props.trend > 0 ? TrendingUp : TrendingDown
})

const trendColor = computed(() => {
  if (props.trend === 0) return 'text-slate-400'
  return props.trend > 0 ? 'text-accent-green' : 'text-accent-red'
})

const trendText = computed(() => {
  if (props.trend === 0) return '--'
  return `${props.trend > 0 ? '+' : ''}${props.trend}%`
})
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <template v-if="loading">
      <div class="flex items-center gap-4">
        <div class="w-12 h-12 rounded-full bg-white/5 animate-pulse" />
        <div class="flex-1 space-y-2">
          <div class="h-4 w-20 bg-white/5 rounded animate-pulse" />
          <div class="h-6 w-28 bg-white/5 rounded animate-pulse" />
        </div>
      </div>
    </template>
    <template v-else>
      <div class="flex items-center gap-4">
        <div :class="cn('w-12 h-12 rounded-full flex items-center justify-center', color)">
          <component :is="icon" class="w-5 h-5 text-white" />
        </div>
        <div class="flex-1 min-w-0">
          <p class="text-sm text-text-secondary truncate">{{ title }}</p>
          <div class="flex items-baseline gap-1">
            <span class="text-2xl font-bold text-text-primary">{{ formattedValue }}</span>
            <span v-if="unit" class="text-sm text-text-secondary">{{ unit }}</span>
          </div>
        </div>
      </div>
      <div v-if="trend !== 0 || trend === 0" class="mt-3 flex items-center gap-1 text-xs">
        <component :is="trendIcon" :class="trendColor" class="w-3.5 h-3.5" />
        <span :class="trendColor">{{ trendText }}</span>
        <span class="text-text-muted ml-1">vs 上期</span>
      </div>
    </template>
  </div>
</template>
