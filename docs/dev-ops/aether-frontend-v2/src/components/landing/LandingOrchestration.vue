<script setup lang="ts">
import SectionEyebrow from './SectionEyebrow.vue'

const CHIPS = ['自动路由', '并行执行', '循环迭代', '工具编排']

const ORCH_GROUPS = [
  {
    label: '执行中',
    count: 4,
    dot: '#ffffff',
    items: ['CodeAgent — 代码审查', 'DataAgent — 数据清洗'],
  },
  {
    label: '等待',
    count: 7,
    dot: '#e5e5e5',
    items: ['DocAgent — 等待上游完成', 'TestAgent — 排队中'],
  },
  {
    label: '完成',
    count: 18,
    dot: '#a3a3a3',
    items: ['OpsAgent — 健康检查通过', 'MailAgent — 摘要已生成'],
  },
  {
    label: '归档',
    count: 13,
    dot: '#525252',
    items: ['历史任务 · 批量归档 · 日志转储'],
  },
]
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-20 md:py-28">
    <div class="grid md:grid-cols-2 gap-10 md:gap-16 items-start">
      <div
        v-motion
        :initial="{ opacity: 0, y: 20 }"
        :visible="{ opacity: 1, y: 0, transition: { duration: 700, ease: [0.22, 1, 0.36, 1] } }"
      >
        <SectionEyebrow label="编排" tag="AI 原生" />
        <h2 class="mt-5 text-3xl md:text-5xl font-semibold tracking-tight leading-[1.02]">
          一个工作流，
          <br />
          编排所有智能体。
        </h2>
        <p class="mt-6 text-white/60 text-base leading-[1.6] max-w-md">
          Aether 的 DAG 工作流引擎支持串行、并行、循环三种编排模式。
          定义节点间的数据流转，智能体协作从未如此简单。
        </p>
        <div class="mt-6 flex flex-wrap gap-2">
          <span
            v-for="chip in CHIPS"
            :key="chip"
            class="text-xs text-white/70 px-3 py-1.5 rounded-full border border-white/10 bg-white/[0.03]"
          >
            {{ chip }}
          </span>
        </div>
      </div>

      <div
        v-motion
        :initial="{ opacity: 0, y: 20 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: 100, duration: 700, ease: [0.22, 1, 0.36, 1] } }"
        class="liquid-glass rounded-2xl p-5"
      >
        <p class="text-xs text-white/50 mb-4">今日 · 42 个任务已执行</p>
        <div class="flex flex-col gap-3">
          <div v-for="group in ORCH_GROUPS" :key="group.label" class="liquid-glass rounded-lg p-3">
            <div class="flex items-center gap-2 mb-2">
              <span class="w-2 h-2 rounded-full" :style="{ backgroundColor: group.dot }" />
              <span class="text-xs text-white/80 font-medium">
                {{ group.label }}
                <span class="text-white/40">({{ group.count }})</span>
              </span>
            </div>
            <ul class="flex flex-col gap-1">
              <li v-for="item in group.items" :key="item" class="text-[11px] text-white/50 pl-4">
                · {{ item }}
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
