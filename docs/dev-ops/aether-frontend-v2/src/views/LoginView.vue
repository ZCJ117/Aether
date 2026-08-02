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
  const result = await auth.login(username.value, password.value)
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
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-icon">A</div>
        <h1 class="brand-name">Aether</h1>
        <p class="brand-desc">Agent Platform</p>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="field">
          <label class="field-label">用户名</label>
          <input
            v-model="username"
            type="text"
            placeholder="admin"
            autocomplete="username"
            :disabled="loading"
            class="field-input"
          />
        </div>
        <div class="field">
          <label class="field-label">密码</label>
          <input
            v-model="password"
            type="password"
            placeholder="••••••"
            autocomplete="current-password"
            :disabled="loading"
            class="field-input"
          />
        </div>

        <div v-if="error" class="error-msg">{{ error }}</div>

        <button type="submit" class="submit-btn" :disabled="loading || !username || !password">
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #000;
  padding: 1rem;
}

.login-card {
  width: 100%;
  max-width: 360px;
  background: rgba(28, 28, 30, 0.85);
  backdrop-filter: blur(30px) saturate(200%);
  -webkit-backdrop-filter: blur(30px) saturate(200%);
  border-radius: 20px;
  padding: 36px 32px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.4);
}

.brand {
  text-align: center;
  margin-bottom: 28px;
}

.brand-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: rgba(90, 200, 250, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 12px;
  font-size: 22px;
  font-weight: 700;
  color: #5AC8FA;
}

.brand-name {
  font-size: 22px;
  font-weight: 700;
  color: #F5F5F7;
  margin: 0;
}

.brand-desc {
  font-size: 12px;
  color: #636366;
  margin: 4px 0 0;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 11px;
  color: #98989D;
}

.field-input {
  padding: 10px 12px;
  border-radius: 10px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: rgba(44, 44, 46, 0.6);
  color: #F5F5F7;
  font-size: 14px;
  outline: none;
  font-family: inherit;
}

.field-input:focus {
  border-color: #5AC8FA;
}

.field-input:disabled {
  opacity: 0.5;
}

.error-msg {
  padding: 8px 12px;
  border-radius: 8px;
  background: rgba(255, 69, 58, 0.08);
  color: #FF453A;
  font-size: 12px;
  text-align: center;
}

.submit-btn {
  margin-top: 6px;
  width: 100%;
  padding: 11px;
  border-radius: 10px;
  border: none;
  background: #5AC8FA;
  color: #000;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
  transition: opacity 0.15s;
}

.submit-btn:hover {
  opacity: 0.88;
}

.submit-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
