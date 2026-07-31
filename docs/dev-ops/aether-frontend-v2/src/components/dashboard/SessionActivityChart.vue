<script setup lang="ts">
import { watch, onMounted } from 'vue'
import { useChart } from '@/composables/useChart'
import type { SessionActivityPoint } from '@/types/dashboard'

const props = withDefaults(defineProps<{
  data: SessionActivityPoint[]
  loading?: boolean
}>(), {
  loading: false,
})

const { chartRef, initChart, setOption } = useChart()

function buildOption(): void {
  const hours = props.data.map((d) => `${d.hour}:00`)
  const counts = props.data.map((d) => d.count)

  setOption({
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1a1a1a',
      borderColor: 'rgba(255,255,255,0.05)',
      textStyle: { color: '#DEDBC8', fontSize: 12 },
      formatter: (params: any) => {
        const p = params[0]
        return `${p.name}<br/>会话数: <b>${p.value}</b>`
      },
    },
    grid: { left: 10, right: 20, top: 10, bottom: 25 },
    xAxis: {
      type: 'category',
      data: hours,
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.05)' } },
      axisTick: { show: false },
      axisLabel: { color: '#8b8b7a', fontSize: 10, interval: 3 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.03)' } },
      axisLabel: { color: '#8b8b7a', fontSize: 10 },
    },
    series: [
      {
        type: 'bar',
        data: counts,
        itemStyle: {
          color: '#3b82f6',
          borderRadius: [4, 4, 0, 0],
        },
        barWidth: '60%',
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
    <h3 class="text-sm font-medium text-text-primary mb-4">今日会话活跃度</h3>
    <div v-if="loading" class="h-48 flex items-center justify-center">
      <div class="animate-spin w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full" />
    </div>
    <div v-else ref="chartRef" class="h-48 w-full" />
  </div>
</template>
