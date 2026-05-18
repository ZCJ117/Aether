<script setup>
import BrandMark from '@/components/common/BrandMark.vue'
import BlurRevealText from '@/components/common/BlurRevealText.vue'
import ScrollReveal from '@/components/common/ScrollReveal.vue'
import StaggerReveal from '@/components/common/StaggerReveal.vue'
import OrbitRing from '@/components/common/OrbitRing.vue'
import ParticleGrid from '@/components/common/ParticleGrid.vue'
import FeatureItem from './FeatureItem.vue'

const orbitBadges = ['LLM', 'RAG', 'API', 'MCP', '工具', '工作流']
</script>

<template>
  <section class="hero" aria-label="产品介绍">
    <div class="hero-bg">
      <ParticleGrid :cols="22" :rows="16" :cell-size="26" :max-opacity="0.45" />
    </div>

    <div class="hero-brand-wrap">
      <BrandMark size="md" class="hero-brand-float" />
    </div>

    <BlurRevealText as="h1" text="你的智能助手" :stagger-ms="55" />

    <ScrollReveal>
      <p class="hero-sub">
        多模型、多工具、多工作流 — 一个能帮你把事做完的企业级 AI Agent 平台。
      </p>
    </ScrollReveal>

    <StaggerReveal>
      <FeatureItem title="灵活的工作流编排" description="顺序、并行、循环 — 按需组合 Agent 与工具调用链路">
        <template #icon>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 11 12 14 22 4" />
            <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
          </svg>
        </template>
      </FeatureItem>
      <FeatureItem title="全链路可观测" description="对话、工具调用、成本 — 每一步都可追溯与审计">
        <template #icon>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
            <line x1="8" y1="21" x2="16" y2="21" />
            <line x1="12" y1="17" x2="12" y2="21" />
          </svg>
        </template>
      </FeatureItem>
    </StaggerReveal>

    <ScrollReveal>
      <div class="hero-visual-wrap">
        <OrbitRing :radius="115" :speed="22" :count="6" :start-angle-offset="30">
          <template #center>
            <div class="orbit-center-mark">
              <BrandMark size="sm" />
            </div>
          </template>
          <template #item="{ index }">
            <span class="orbit-badge">{{ orbitBadges[index] }}</span>
          </template>
        </OrbitRing>
      </div>
    </ScrollReveal>
  </section>
</template>

<style scoped>
.hero {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding-right: 20px;
  position: relative;
}

.hero-bg {
  position: absolute;
  inset: -40px -40px -60px -40px;
  z-index: 0;
  pointer-events: none;
}

.hero > *:not(.hero-bg) {
  position: relative;
  z-index: 1;
}

.hero-brand-wrap {
  margin-bottom: -8px;
}

.hero-brand-float {
  animation: float 4s var(--ease-in-out-smooth) infinite;
  display: inline-block;
}

.hero h1 {
  font-size: 36px;
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.3px;
  color: var(--text);
}

.hero-sub {
  font-size: 17px;
  line-height: 1.6;
  color: var(--text-secondary);
  max-width: 42ch;
}

.hero-visual-wrap {
  width: 100%;
  aspect-ratio: 1;
  max-width: 360px;
  margin: 8px auto 0;
  display: grid;
  place-items: center;
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-md);
  padding: 16px;
  transition: transform 0.4s var(--ease-out-expo),
    box-shadow 0.4s var(--ease-out-expo);
}

.hero-visual-wrap:hover {
  transform: translateY(-3px) scale(1.01);
  box-shadow: var(--elevation-hover);
}

.orbit-center-mark {
  display: flex;
  align-items: center;
  justify-content: center;
}

.orbit-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  font-size: 11px;
  font-weight: 600;
  color: var(--text);
  background: var(--surface);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm);
  white-space: nowrap;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.orbit-badge:hover {
  transform: scale(1.15);
  box-shadow: var(--shadow-md);
}

@media (max-width: 767px) {
  .hero {
    padding-right: 0;
    gap: 16px;
    text-align: center;
    align-items: center;
  }

  .hero h1 {
    font-size: 28px;
  }

  .hero-sub {
    font-size: 15px;
    max-width: 38ch;
  }

  .hero-visual-wrap {
    max-width: 300px;
    padding: 12px;
  }

  .orb-badge {
    font-size: 10px;
    padding: 2px 8px;
  }
}
</style>
