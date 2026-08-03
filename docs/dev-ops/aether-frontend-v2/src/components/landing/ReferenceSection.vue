<script setup lang="ts">
const frameworks = [
  {
    name: 'AutoGen',
    org: 'Microsoft Research',
    lang: 'Python',
    borrowed: 'Agent 协议 · DiGraph 图执行 · 多模型路由 · OTel 追踪 · PlanActAgent 模式',
    color: 'border-[#5AC8FA]',
  },
  {
    name: 'AgentScope Java',
    org: 'Alibaba',
    lang: 'Java',
    borrowed: 'AgentState 双模状态 · Hook 拦截系统 · 中间件洋葱模型 · Agent 工具作用域 · 权限引擎',
    color: 'border-[#30D158]',
  },
  {
    name: 'CrewAI',
    org: 'CrewAI Inc.',
    lang: 'Python',
    borrowed: 'BaseAgent 序列化 · CheckpointConfig 检查点 · Memory 三模召回 · SchemaHint 反馈 · EventBus',
    color: 'border-[#FF9F0A]',
  },
  {
    name: 'MetaGPT',
    org: 'DeepWisdom',
    lang: 'Python',
    borrowed: 'RoleContext 上下文 · Memory 分层 · ActionNode 编译校验 · PLAN_AND_ACT · 消息去重',
    color: 'border-[#BF5AF2]',
  },
  {
    name: 'cc-haha',
    org: 'Claude Code Fork',
    lang: 'TypeScript',
    borrowed: 'Token 成本追踪 · SessionMemory 后台提取 · 工具白名单 · WAL 日志 · autoCompact 熔断',
    color: 'border-[#FF453A]',
  },
  {
    name: 'Hermes Agent',
    org: 'Nous Research',
    lang: 'Python',
    borrowed: 'FailoverReason 14 类分类 · 抖动退避算法 · 模型故障转移链 · 摘要防污染标注',
    color: 'border-[#FFD60A]',
  },
]
</script>

<template>
  <section id="references" class="max-w-6xl mx-auto px-6 py-20 md:py-32">
    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 800, ease: [0.22, 1, 0.36, 1] } }"
    >
      <h2 class="text-3xl md:text-5xl font-semibold tracking-tight text-white text-center mb-4">
        设计参考来源
      </h2>
      <p class="text-white/60 text-center max-w-lg mx-auto mb-16 text-sm leading-relaxed">
        参考 6 个开源 Agent 框架，共研读 100+ 源文件，编写 9 个测试类（93 个测试用例）
      </p>
    </div>

    <div class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="(fw, i) in frameworks"
        :key="fw.name"
        v-motion
        :initial="{ opacity: 0, y: 24 }"
        :visible="{ opacity: 1, y: 0, transition: { delay: i * 0.1, duration: 600, ease: [0.22, 1, 0.36, 1] } }"
        class="bg-white/[0.03] border border-white/10 rounded-2xl p-6 hover:bg-white/[0.06] transition-colors duration-300"
        :class="fw.color"
      >
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-white font-semibold text-base">{{ fw.name }}</h3>
          <span class="text-white/30 text-[10px] font-mono">{{ fw.lang }}</span>
        </div>
        <p class="text-white/40 text-[11px] mb-3">{{ fw.org }}</p>
        <p class="text-white/50 text-xs leading-relaxed">{{ fw.borrowed }}</p>
      </div>
    </div>
  </section>
</template>
