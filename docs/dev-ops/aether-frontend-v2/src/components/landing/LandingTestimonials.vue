<script setup lang="ts">
const TESTIMONIALS = [
  {
    quote: 'Aether 的 DAG 编排引擎让我们的多 Agent 协作系统从概念验证到生产部署只用了两周。远超预期。',
    name: '张明',
    role: 'AI 平台技术负责人',
    company: '某头部金融科技公司',
  },
  {
    quote: 'ReAct 推理循环 + 工具作用域的设计非常优雅。我们团队现在可以专注于业务逻辑，而不是 Agent 基础设施。',
    name: '李华',
    role: '高级算法工程师',
    company: '某 AI 独角兽企业',
  },
  {
    quote: '上下文管理和自动压缩机制解决了我们长期以来的 Token 成本问题。生产环境的稳定性令人放心。',
    name: '王芳',
    role: '工程效能负责人',
    company: '某大型互联网公司',
  },
]
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-20 md:py-28 border-t border-white/10">
    <div class="grid md:grid-cols-3 gap-6">
      <figure
        v-for="(t, i) in TESTIMONIALS"
        :key="t.name"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 80, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="liquid-glass rounded-2xl p-6"
      >
        <blockquote class="text-sm text-white/80 leading-[1.6]">
          "{{ t.quote }}"
        </blockquote>
        <figcaption class="mt-6 pt-5 border-t border-white/10">
          <div class="text-sm font-semibold text-white">{{ t.name }}</div>
          <div class="text-xs text-white/50 mt-0.5">{{ t.role }}</div>
          <div class="text-xs text-white font-semibold tracking-wide mt-2">{{ t.company }}</div>
        </figcaption>
      </figure>
    </div>
  </section>
</template>
