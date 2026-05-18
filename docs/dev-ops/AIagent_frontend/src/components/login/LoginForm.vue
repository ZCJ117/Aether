<script setup>
import { ref } from 'vue'
import BlurRevealText from '@/components/common/BlurRevealText.vue'
import AppButton from '@/components/common/AppButton.vue'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const router = useRouter()

const username = ref('admin')
const password = ref('admin')
const status = ref({ text: '', type: 'info' })

function setStatus(text, type = 'info') {
  status.value = { text, type }
}

function markInputError(el) {
  el.classList.add('error')
  setTimeout(() => el.classList.remove('error'), 1200)
}

function handleSubmit() {
  setStatus('')

  const u = username.value.trim()
  const p = password.value

  if (!u || !p) {
    setStatus('请输入账号与密码。', 'error')
    return
  }

  const result = auth.login(u, p)
  if (!result.ok) {
    setStatus(result.error, 'error')
    return
  }

  setStatus('登录成功，正在跳转…', 'info')
  setTimeout(() => router.replace({ name: 'Chat' }), 300)
}

function handleForgot(e) {
  e.preventDefault()
  setStatus('演示环境不支持重置密码，请使用 admin / admin 登录。')
}
</script>

<template>
  <div class="card">
    <BlurRevealText as="h2" text="登录" :stagger-ms="55" />

    <form @submit.prevent="handleSubmit" autocomplete="on">
      <div class="field">
        <label for="username">账号</label>
        <input
          id="username"
          v-model="username"
          type="text"
          placeholder="输入账号"
          autocomplete="username"
        />
      </div>

      <div class="field">
        <label for="password">密码</label>
        <input
          id="password"
          v-model="password"
          type="password"
          placeholder="输入密码"
          autocomplete="current-password"
        />
      </div>

      <div
        :class="['msg', { error: status.type === 'error' }]"
        role="status"
        aria-live="polite"
      >
        {{ status.text }}
      </div>

      <AppButton variant="solid" type="submit" style="width: 100%">
        登录
      </AppButton>
    </form>

    <a class="forgot" href="#" @click="handleForgot">忘记密码？</a>

    <p class="footer">演示账号：admin / admin</p>
  </div>
</template>

<style scoped>
.card {
  width: 100%;
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: 40px 36px 36px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  animation: slideUp 0.6s ease both;
  transition: transform 0.35s cubic-bezier(0.25, 0.1, 0.25, 1),
    box-shadow 0.35s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.10);
}

.card h2 {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.2px;
  color: var(--text);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-bottom: 14px;
}

.field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text);
  letter-spacing: 0.1px;
}

.field input {
  width: 100%;
  height: 46px;
  padding: 0 15px;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-md);
  font-family: var(--font);
  font-size: 15px;
  color: var(--text);
  background: #fff;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
  -webkit-appearance: none;
}

.field input::placeholder {
  color: var(--text-tertiary);
}

.field input:focus {
  border-color: var(--text);
  animation: ring-glow 0.6s var(--ease-out-expo);
}

.field input.error {
  border-color: var(--error);
  box-shadow: 0 0 0 3px rgba(255, 59, 48, 0.1);
}

.msg {
  min-height: 18px;
  font-size: 13px;
  color: var(--text-secondary);
  text-align: center;
  transition: color 0.2s ease;
  margin-bottom: 8px;
}

.msg.error {
  color: var(--error);
}

.forgot {
  display: block;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
  text-decoration: none;
  transition: color 0.18s ease;
  margin-top: -8px;
}

.forgot:hover {
  color: var(--text);
}

.footer {
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

@media (max-width: 767px) {
  .card {
    padding: 28px 22px;
  }
}
</style>
