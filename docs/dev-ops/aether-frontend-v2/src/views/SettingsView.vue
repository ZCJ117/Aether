<script setup lang="ts">
import { ref, computed } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'

const ui = useUiStore()
const auth = useAuthStore()

const apiTokenInput = ref(auth.apiToken)
const connectionUrl = ref('http://localhost:8080')

const maskedToken = computed(() => {
  const t = auth.apiToken
  if (t.length <= 8) return t
  return t.slice(0, 4) + '****' + t.slice(-4)
})

function handleSaveToken() {
  auth.setToken(apiTokenInput.value)
}
</script>

<template>
  <div class="settings">
    <div class="page-header">
      <h1>设置</h1>
    </div>

    <div class="settings-sections">
      <!-- API Token -->
      <section class="settings-section">
        <h2>API Token</h2>
        <div class="setting-row">
          <label>当前 Token</label>
          <span v-if="auth.isValidToken" class="token-display">{{ maskedToken }}</span>
          <span v-else class="token-missing">未设置</span>
        </div>
        <div class="setting-row">
          <label>新 Token</label>
          <div class="token-input-group">
            <input
              v-model="apiTokenInput"
              type="password"
              placeholder="输入新的 API Token"
            />
            <button @click="handleSaveToken">保存</button>
          </div>
        </div>
      </section>

      <!-- Theme -->
      <section class="settings-section">
        <h2>主题</h2>
        <div class="setting-row">
          <label>当前主题</label>
          <div class="theme-toggle">
            <button
              :class="['theme-btn', { active: ui.theme === 'dark' }]"
              @click="ui.setTheme('dark')"
            >
              深色
            </button>
            <button
              :class="['theme-btn', { active: ui.theme === 'light' }]"
              @click="ui.setTheme('light')"
            >
              浅色
            </button>
          </div>
        </div>
      </section>

      <!-- Connection URL -->
      <section class="settings-section">
        <h2>连接地址</h2>
        <div class="setting-row">
          <label>后端 URL</label>
          <input
            v-model="connectionUrl"
            type="text"
            placeholder="http://localhost:8080"
          />
        </div>
      </section>

      <!-- Locale -->
      <section class="settings-section">
        <h2>语言</h2>
        <div class="setting-row">
          <label>界面语言</label>
          <div class="locale-toggle">
            <button
              :class="['locale-btn', { active: ui.locale === 'zh-CN' }]"
              @click="ui.setLocale('zh-CN')"
            >
              中文
            </button>
            <button
              :class="['locale-btn', { active: ui.locale === 'en' }]"
              @click="ui.setLocale('en')"
            >
              English
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.settings {
  padding: 1.5rem;
}

.page-header {
  margin-bottom: 1.5rem;
}

.page-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.settings-sections {
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.settings-section {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.25rem;
}

.settings-section h2 {
  margin: 0 0 1rem;
  font-size: 1rem;
  color: var(--text-primary, #eee);
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--border-color, #2a2a4a);
}

.setting-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0;
}

.setting-row + .setting-row {
  border-top: 1px solid var(--border-color, #2a2a4a);
  padding-top: 0.75rem;
}

.setting-row label {
  font-size: 0.875rem;
  color: var(--text-secondary, #aaa);
}

.token-display {
  font-family: monospace;
  font-size: 0.8125rem;
  color: var(--text-primary, #eee);
}

.token-missing {
  font-size: 0.8125rem;
  color: #ef4444;
}

.token-input-group {
  display: flex;
  gap: 0.5rem;
}

.token-input-group input {
  width: 260px;
  padding: 0.5rem 0.625rem;
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 6px;
  background: var(--bg-primary, #0f0f1a);
  color: var(--text-primary, #eee);
  font-size: 0.8125rem;
  outline: none;
}

.token-input-group input:focus {
  border-color: var(--accent-color, #6366f1);
}

.token-input-group button {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 6px;
  background: var(--accent-color, #6366f1);
  color: #fff;
  font-size: 0.8125rem;
  cursor: pointer;
  transition: opacity 0.2s;
}

.token-input-group button:hover {
  opacity: 0.9;
}

.theme-toggle, .locale-toggle {
  display: flex;
  gap: 0.25rem;
  background: var(--bg-primary, #0f0f1a);
  border-radius: 8px;
  padding: 2px;
}

.theme-btn, .locale-btn {
  padding: 0.375rem 0.875rem;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary, #888);
  font-size: 0.8125rem;
  cursor: pointer;
  transition: all 0.2s;
}

.theme-btn.active, .locale-btn.active {
  background: var(--accent-color, #6366f1);
  color: #fff;
}

.theme-btn:hover:not(.active), .locale-btn:hover:not(.active) {
  color: var(--text-primary, #eee);
}

.settings-section input[type="text"] {
  width: 300px;
  padding: 0.5rem 0.625rem;
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 6px;
  background: var(--bg-primary, #0f0f1a);
  color: var(--text-primary, #eee);
  font-size: 0.8125rem;
  outline: none;
}

.settings-section input[type="text"]:focus {
  border-color: var(--accent-color, #6366f1);
}
</style>
