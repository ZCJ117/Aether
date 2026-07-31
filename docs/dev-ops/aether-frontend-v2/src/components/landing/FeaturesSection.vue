<script setup lang="ts">
import { Bot, Zap, Layers } from 'lucide-vue-next'

interface FeatureCard {
  icon: typeof Bot
  title: string
  description: string
}

const features: FeatureCard[] = [
  {
    icon: Bot,
    title: 'Multi-Agent Orchestration',
    description:
      '构建复杂的多智能体协作工作流。通过 DAG 工作流引擎编排 Agent 之间的消息路由、条件分支和并行执行，实现生产级的智能体协同。',
  },
  {
    icon: Zap,
    title: 'Streaming SSE Protocol',
    description:
      '基于 Server-Sent Events 的实时流式通信协议。低延迟推送 Agent 思考过程、工具调用和最终响应，提供流畅的对话体验。',
  },
  {
    icon: Layers,
    title: 'DDD Architecture',
    description:
      '采用领域驱动设计（DDD）架构，清晰的限界上下文划分。支持模型、记忆、技能、会话等核心域的独立演进和扩展。',
  },
]
</script>

<template>
  <section class="py-24 px-4 bg-surface-base">
    <div class="max-w-5xl mx-auto">
      <!-- Section header -->
      <div class="text-center mb-14">
        <h2 class="text-3xl font-bold text-primary mb-3">Key Features</h2>
        <p class="text-sm text-secondary/60 max-w-lg mx-auto">
          为生产环境打造的核心能力，覆盖多智能体应用的全生命周期
        </p>
      </div>

      <!-- Feature cards -->
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div
          v-for="feature in features"
          :key="feature.title"
          class="group relative p-6 rounded-xl border border-white/5
                 bg-surface-card hover:border-primary/20 transition-all duration-300
                 hover:shadow-[0_4px_24px_rgba(222,219,200,0.04)]"
        >
          <!-- Icon -->
          <div
            class="w-10 h-10 rounded-lg bg-primary/5 border border-primary/10
                   flex items-center justify-center mb-4
                   group-hover:bg-primary/10 group-hover:border-primary/20 transition-all"
          >
            <component
              :is="feature.icon"
              class="w-5 h-5 text-primary/70 group-hover:text-primary transition-colors"
            />
          </div>

          <!-- Title -->
          <h3 class="text-sm font-semibold text-primary mb-2">
            {{ feature.title }}
          </h3>

          <!-- Description -->
          <p class="text-xs text-secondary/60 leading-relaxed">
            {{ feature.description }}
          </p>
        </div>
      </div>
    </div>
  </section>
</template>
