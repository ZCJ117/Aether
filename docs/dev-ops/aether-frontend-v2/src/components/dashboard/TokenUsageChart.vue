<script setup lang="ts">
import { watch, onMounted } from 'vue'
import { useChart } from '@/composables/useChart'
import type { TokenUsagePoint } from '@/types/dashboard'

const props = withDefaults(defineProps<{
  data: TokenUsagePoint[]
  period: string
  loading?: boolean
}>(), {
  loading: false,
})

const { chartRef, initChart, setOption } = useChart()

function buildOption(): void {
  const dates = props.data.map((d) => d.date)
  const inputData = props.data.map((d) => d.inputTokens)
  const outputData = props.data.map((d) => d.outputTokens)

  setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a1a1a',
      borderColor: 'rgba(255,255,255,0.05)',
      textStyle: { color: '#DEDBC8', fontSize: 12 },
    },
    legend: {
      data: ['输入 Token', '输出 Token'],
      bottom: 0,
      textStyle: { color: '#8b8b7a', fontSize: 11 },
    },
    grid: { left: 10, right: 20, top: 10, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
      axisTick: { show: false },
      axisLabel: { color: '#8b8b7a', fontSize: 10, rotate: 45 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.03)' } },
      axisLabel: {
        color: '#8b8b7a',
        fontSize: 10,
        formatter: (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(0)}k` : `${v}`),
      },
    },
    series: [
      {
        name: '输入 Token',
        type: 'line',
        data: inputData,
        smooth: true,
        symbol: 'none',
        lineStyle: { color: '#3b82f6', width: 2 },
        areaStyle: { color: 'rgba(59,130,246,0.08)' },
      },
      {
        name: '输出 Token',
        type: 'line',
        data: outputData,
        smooth: true,
        symbol: 'none',
        lineStyle: { color: '#22c55e', width: 2 },
        areaStyle: { color: 'rgba(34,197,94,0.08)' },
      },
    ],
  })
}

watch(() => props.data, () => {
  if (chartRef.value) buildOption()
}, { deep: true })

onMounted(() => {
  initChart('dark')
  buildOption()
})
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <div class="flex items-center justify-between mb-4">
      <h3 class="text-sm font-medium text-text-primary">Token 用量趋势</h3>
      <span class="text-xs text-text-muted">{{ period }}</span>
    </div>
    <div v-if="loading" class="h-64 flex items-center justify-center">
      <div class="animate-spin w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full" />
    </div>
    <div v-else ref="chartRef" class="h-64 w-full" />
  </div>
</template>
