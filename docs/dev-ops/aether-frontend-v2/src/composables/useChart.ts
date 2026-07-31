import { ref, onMounted, onUnmounted, type Ref } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'

export function useChart(): {
  chartRef: Ref<HTMLElement | null>
  initChart: (theme?: string) => echarts.ECharts | null
  setOption: (option: EChartsOption, notMerge?: boolean) => void
  resize: () => void
  dispose: () => void
} {
  const chartRef = ref<HTMLElement | null>(null)
  let instance: echarts.ECharts | null = null

  function initChart(theme?: string): echarts.ECharts | null {
    if (!chartRef.value) return null

    if (instance) {
      instance.dispose()
    }

    instance = echarts.init(chartRef.value, theme)
    return instance
  }

  function setOption(option: EChartsOption, notMerge?: boolean): void {
    if (!instance) return
    instance.setOption(option, { notMerge })
  }

  function resize(): void {
    if (!instance) return
    instance.resize()
  }

  function dispose(): void {
    if (instance) {
      instance.dispose()
      instance = null
    }
  }

  function onWindowResize(): void {
    resize()
  }

  onMounted(() => {
    window.addEventListener('resize', onWindowResize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', onWindowResize)
    dispose()
  })

  return {
    chartRef,
    initChart,
    setOption,
    resize,
    dispose,
  }
}
