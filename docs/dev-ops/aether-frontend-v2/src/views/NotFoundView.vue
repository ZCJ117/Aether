<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome() {
  router.push({ name: 'Landing' })
}
</script>

<template>
  <div class="not-found">
    <div class="not-found-card">
      <div class="error-code">404</div>
      <h1>页面未找到</h1>
      <p>您访问的页面不存在或已被移除</p>
      <button class="btn-home" @click="goHome">返回首页</button>
    </div>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--bg-primary, #0f0f1a);
  padding: 1rem;
}

.not-found-card {
  text-align: center;
}

.error-code {
  font-size: 6rem;
  font-weight: 800;
  color: var(--accent-color, #6366f1);
  line-height: 1;
  margin-bottom: 0.5rem;
  opacity: 0.5;
}

h1 {
  font-size: 1.5rem;
  margin: 0 0 0.5rem;
  color: var(--text-primary, #eee);
}

p {
  margin: 0 0 1.5rem;
  color: var(--text-secondary, #888);
  font-size: 0.9375rem;
}

.btn-home {
  padding: 0.625rem 1.5rem;
  border: none;
  border-radius: 8px;
  background: var(--accent-color, #6366f1);
  color: #fff;
  font-size: 0.875rem;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-home:hover {
  opacity: 0.9;
}
</style>
