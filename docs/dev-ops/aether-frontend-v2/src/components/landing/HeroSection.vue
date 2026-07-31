<script setup lang="ts">
import { ArrowRight, Github } from 'lucide-vue-next'
</script>

<template>
  <section class="relative min-h-screen flex items-center justify-center overflow-hidden bg-surface-ink">
    <!-- Radial gradient backgrounds -->
    <div
      class="absolute inset-0 bg-[radial-gradient(ellipse_at_top_left,rgba(96,165,250,0.12),transparent_50%)]"
    />
    <div
      class="absolute inset-0 bg-[radial-gradient(ellipse_at_bottom_right,rgba(167,139,250,0.10),transparent_50%)]"
    />
    <div
      class="absolute inset-0 bg-[radial-gradient(ellipse_at_center,rgba(222,219,200,0.04),transparent_70%)]"
    />

    <!-- Grid overlay -->
    <div class="absolute inset-0 opacity-[0.03]">
      <div
        class="h-full w-full"
        style="background-image: linear-gradient(rgba(222,219,200,1) 1px, transparent 1px), linear-gradient(90deg, rgba(222,219,200,1) 1px, transparent 1px); background-size: 60px 60px;"
      />
    </div>

    <!-- Floating particles -->
    <div class="absolute inset-0 pointer-events-none">
      <div class="absolute w-3 h-3 rounded-full bg-accent-blue/20"
           style="top: 15%; left: 10%; animation: float-1 8s ease-in-out infinite;" />
      <div class="absolute w-2 h-2 rounded-full bg-accent-purple/15"
           style="top: 25%; right: 20%; animation: float-2 10s ease-in-out infinite;" />
      <div class="absolute w-4 h-4 rounded-full bg-primary/8"
           style="top: 60%; left: 15%; animation: float-3 9s ease-in-out infinite;" />
      <div class="absolute w-2.5 h-2.5 rounded-full bg-accent-green/12"
           style="top: 70%; right: 15%; animation: float-4 11s ease-in-out infinite;" />
      <div class="absolute w-3.5 h-3.5 rounded-full bg-accent-yellow/10"
           style="top: 40%; left: 50%; animation: float-5 7s ease-in-out infinite;" />
      <div class="absolute w-2 h-2 rounded-full bg-accent-blue/12"
           style="top: 10%; right: 40%; animation: float-1 12s ease-in-out infinite;" />
      <div class="absolute w-3 h-3 rounded-full bg-accent-red/8"
           style="top: 80%; left: 40%; animation: float-3 9.5s ease-in-out infinite;" />
    </div>

    <!-- Content -->
    <div class="relative z-10 text-center px-4 max-w-3xl mx-auto">
      <!-- Badge -->
      <div
        class="inline-flex items-center gap-2 px-3 py-1 rounded-full border border-primary/10
               bg-primary/5 text-xs text-primary/60 mb-8"
      >
        <span class="w-1.5 h-1.5 rounded-full bg-accent-green animate-pulse" />
        v2.0 Beta Now Available
      </div>

      <!-- Title -->
      <h1
        class="text-5xl sm:text-6xl md:text-7xl font-bold tracking-tight mb-6
               bg-gradient-to-br from-primary via-primary/90 to-accent-blue bg-clip-text text-transparent"
      >
        Aether
      </h1>

      <!-- Subtitle -->
      <p class="text-lg sm:text-xl text-secondary mb-3 font-medium">
        Production-grade Multi-Agent AI Runtime
      </p>

      <!-- Description -->
      <p class="text-sm sm:text-base text-secondary/60 max-w-xl mx-auto mb-10 leading-relaxed">
        基于 DDD 架构的下一代多智能体运行时平台。编排、监控并扩展您的 AI Agent
        工作流，提供流式 SSE 协议、DAG 工作流引擎和开箱即用的生产级能力。
      </p>

      <!-- CTA Buttons -->
      <div class="flex items-center justify-center gap-4">
        <router-link
          to="/login"
          class="inline-flex items-center gap-2 px-6 py-3 rounded-lg bg-primary text-surface-ink
                 text-sm font-semibold hover:bg-primary/90 transition-all
                 hover:shadow-[0_0_30px_rgba(222,219,200,0.15)]"
        >
          开始使用
          <ArrowRight class="w-4 h-4" />
        </router-link>

        <a
          href="https://github.com"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center gap-2 px-6 py-3 rounded-lg border border-white/10
                 text-sm font-medium text-secondary hover:text-primary
                 hover:border-white/20 transition-all"
        >
          <Github class="w-4 h-4" />
          View on GitHub
        </a>
      </div>
    </div>

    <!-- Bottom gradient fade -->
    <div class="absolute bottom-0 left-0 right-0 h-32 bg-gradient-to-t from-surface-ink to-transparent" />
  </section>
</template>

<style scoped>
@keyframes float-1 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.3; }
  25% { transform: translate(30px, -20px) scale(1.1); opacity: 0.5; }
  50% { transform: translate(-10px, -40px) scale(0.9); opacity: 0.2; }
  75% { transform: translate(-25px, -10px) scale(1.05); opacity: 0.4; }
}

@keyframes float-2 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.25; }
  25% { transform: translate(-20px, -30px) scale(0.95); opacity: 0.4; }
  50% { transform: translate(-40px, -5px) scale(1.1); opacity: 0.3; }
  75% { transform: translate(-15px, -25px) scale(0.9); opacity: 0.5; }
}

@keyframes float-3 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.2; }
  33% { transform: translate(20px, -35px) scale(1.15); opacity: 0.35; }
  66% { transform: translate(-15px, -15px) scale(0.85); opacity: 0.25; }
}

@keyframes float-4 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.2; }
  33% { transform: translate(-30px, -25px) scale(1.05); opacity: 0.35; }
  66% { transform: translate(10px, -40px) scale(0.9); opacity: 0.3; }
}

@keyframes float-5 {
  0%, 100% { transform: translate(0, 0) scale(1); opacity: 0.15; }
  50% { transform: translate(0, -45px) scale(1.2); opacity: 0.3; }
}
</style>
