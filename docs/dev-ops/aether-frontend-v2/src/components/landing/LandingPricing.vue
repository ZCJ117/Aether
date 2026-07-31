<script setup lang="ts">
import { ref } from 'vue'

type Plan = {
  name: string
  tier: string
  monthly: string
  yearly: string
  desc: string
  features: string[]
  pro?: boolean
}

const PLANS: Plan[] = [
  {
    name: 'Community',
    tier: '社区版',
    monthly: '免费',
    yearly: '免费',
    desc: '适合个人开发者和学习探索，体验 Aether 核心能力。',
    features: [
      '最多 3 个智能体',
      '基础 ReAct 推理循环',
      'MCP 工具集成',
      '社区支持',
      'Web 管理界面',
    ],
  },
  {
    name: 'Team',
    tier: '团队版',
    monthly: '¥999/月',
    yearly: '¥9,999/年',
    desc: '适合中小团队，需要多 Agent 协作和 DAG 工作流编排。',
    features: [
      '最多 50 个智能体',
      'DAG 工作流编排引擎',
      '上下文自动压缩',
      '团队协作（最多 5 人）',
      '优先技术支持',
    ],
  },
  {
    name: 'Enterprise',
    tier: '企业版',
    monthly: '¥1,999/月',
    yearly: '¥19,999/年',
    desc: '适合大型组织和关键业务场景，需要高级安全与定制能力。',
    features: [
      '无限智能体',
      '自定义工作流模板',
      'SSO + RBAC 权限控制',
      '私有化部署支持',
      '专属客户成功经理',
    ],
    pro: true,
  },
]

const yearly = ref(false)
</script>

<template>
  <section id="pricing" class="lp-pricing-section">
    <svg width="0" height="0" class="absolute" aria-hidden="true">
      <filter id="lp-noise">
        <feTurbulence type="fractalNoise" baseFrequency="0.5" numOctaves="2" stitchTiles="stitch" />
        <feComponentTransfer>
          <feFuncA type="linear" slope="0.075" />
        </feComponentTransfer>
        <feComposite in2="SourceGraphic" operator="in" result="noise" />
        <feBlend in="SourceGraphic" in2="noise" mode="overlay" />
      </filter>
    </svg>

    <div class="lp-watermark-container">
      <div class="lp-watermark-main">
        <span class="lp-watermark-line-1">智能体协作。</span>
        <span class="lp-watermark-line-2">生产级</span>
      </div>
    </div>

    <div
      v-motion
      :initial="{ opacity: 0, y: 24 }"
      :visible="{ opacity: 1, y: 0, transition: { duration: 700, ease: [0.22, 1, 0.36, 1] } }"
      class="lp-grid"
    >
      <div
        v-for="plan in PLANS"
        :key="plan.name"
        :class="['lp-card', plan.pro ? 'lp-card-pro' : '']"
      >
        <div class="lp-tier-small">{{ plan.tier }}</div>
        <div class="lp-tier-large">{{ yearly ? plan.yearly : plan.monthly }}</div>
        <p class="lp-desc">{{ plan.desc }}</p>
        <ul class="lp-list">
          <li v-for="feature in plan.features" :key="feature">
            <span class="lp-check">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <polyline points="5 12 10 17 19 7" />
              </svg>
            </span>
            <span>{{ feature }}</span>
          </li>
        </ul>
        <button type="button" class="lp-btn">选择方案</button>
      </div>
    </div>

    <div class="lp-toggle-wrap">
      <span class="text-xs text-white/60">年付</span>
      <button
        type="button"
        aria-label="Toggle yearly pricing"
        :aria-pressed="yearly"
        :class="['lp-toggle', yearly ? 'active' : '']"
        @click="yearly = !yearly"
      >
        <span class="lp-toggle-knob" />
      </button>
    </div>
  </section>
</template>
