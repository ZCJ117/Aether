<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true

  // Small delay for UX feedback
  await new Promise((r) => setTimeout(r, 300))

  const result = auth.login(username.value, password.value)
  loading.value = false

  if (result.ok) {
    const redirect = (route.query.redirect as string) || '/app/dashboard'
    router.push(redirect)
  } else {
    error.value = result.error || '登录失败，请重试'
  }
}
</script>

<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <h1 class="login-brand">Aether</h1>
        <p class="login-subtitle">Production-grade Multi-Agent AI Runtime</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="form-group">
          <label for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            placeholder="请输入用户名"
            autocomplete="username"
            :disabled="loading"
          />
        </div>

        <div class="form-group">
          <label for="password">密码</label>
          <input
            id="password"
            v-model="password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            :disabled="loading"
          />
        </div>

        <div v-if="error" class="login-error">{{ error }}</div>

        <button type="submit" class="login-btn" :disabled="loading || !username || !password">
          {{ loading ? '登录中...' : '登录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--bg-primary, #0f0f1a);
  padding: 1rem;
}

.login-card {
  width: 100%;
  max-width: 400px;
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 12px;
  padding: 2.5rem 2rem;
}

.login-header {
  text-align: center;
  margin-bottom: 2rem;
}

.login-brand {
  font-size: 2rem;
  font-weight: 700;
  color: var(--accent-color, #6366f1);
  margin: 0;
}

.login-subtitle {
  font-size: 0.875rem;
  color: var(--text-secondary, #888);
  margin: 0.5rem 0 0;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.form-group label {
  font-size: 0.875rem;
  color: var(--text-secondary, #aaa);
}

.form-group input {
  padding: 0.625rem 0.75rem;
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 8px;
  background: var(--bg-primary, #0f0f1a);
  color: var(--text-primary, #eee);
  font-size: 0.9375rem;
  outline: none;
  transition: border-color 0.2s;
}

.form-group input:focus {
  border-color: var(--accent-color, #6366f1);
}

.form-group input:disabled {
  opacity: 0.6;
}

.login-error {
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  color: #ef4444;
  padding: 0.5rem 0.75rem;
  border-radius: 8px;
  font-size: 0.875rem;
}

.login-btn {
  padding: 0.75rem;
  border: none;
  border-radius: 8px;
  background: var(--accent-color, #6366f1);
  color: #fff;
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s;
}

.login-btn:hover {
  opacity: 0.9;
}

.login-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
