<script setup>
import ParticleGrid from '@/components/common/ParticleGrid.vue'
import HeroSection from '@/components/login/HeroSection.vue'
import LoginForm from '@/components/login/LoginForm.vue'
</script>

<template>
  <div class="login-page">
    <ParticleGrid :cols="32" :rows="22" :cell-size="24" :max-opacity="0.5" />
    <main class="page">
      <HeroSection />
      <section class="login-panel" aria-label="登录">
        <LoginForm />
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
  overflow: hidden;
  isolation: isolate;
}

.page {
  width: min(1100px, 100%);
  display: grid;
  grid-template-columns: 1.22fr 0.78fr;
  gap: 40px;
  align-items: center;
  position: relative;
  z-index: 1;
}

.login-panel {
  display: flex;
  align-items: center;
}

@media (max-width: 767px) {
  .login-page {
    align-items: flex-start;
    min-height: 100%;
    padding: 16px;
  }

  .page {
    grid-template-columns: 1fr;
    gap: 28px;
  }
}
</style>
