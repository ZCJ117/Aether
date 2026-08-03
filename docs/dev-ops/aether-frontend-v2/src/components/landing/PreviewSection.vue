<script setup lang="ts">
const previews = [
  {
    title: '仪表盘',
    desc: 'Token 用量 / Agent 状态 / 会话趋势',
    accent: 'border-[#5AC8FA]',
    bg: 'bg-[#5AC8FA]/10',
    label: '[仪表盘截图占位]',
  },
  {
    title: '多 Agent 对话',
    desc: '流式输出 / 工具调用可视化 / 会话管理',
    accent: 'border-[#30D158]',
    bg: 'bg-[#30D158]/10',
    label: '[对话界面截图占位]',
  },
  {
    title: 'Agent 管理',
    desc: 'YAML 配置 / 工具绑定 / 权限控制',
    accent: 'border-[#FF9F0A]',
    bg: 'bg-[#FF9F0A]/10',
    label: '[Agent 配置截图占位]',
  },
]
</script>

<template>
  <section id="preview" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        产品预览
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        Aether 控制台界面一览
      </p>
    </div>

    <div class="grid gap-8 md:grid-cols-3">
      <div
        v-for="(item, i) in previews"
        :key="item.title"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 0.15, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="bg-white/[0.03] border border-white/10 rounded-2xl overflow-hidden hover:border-white/20 transition-colors duration-300"
      >
        <!-- Screenshot placeholder -->
        <div
          class="h-48 flex items-center justify-center"
          :class="[item.accent, item.bg, 'border-b']"
        >
          <span class="text-white/40 text-xs font-medium">{{ item.label }}</span>
        </div>
        <div class="p-5">
          <h3 class="text-white font-semibold text-base mb-1">{{ item.title }}</h3>
          <p class="text-white/50 text-sm leading-relaxed">{{ item.desc }}</p>
        </div>
      </div>
    </div>
  </section>
</template>
