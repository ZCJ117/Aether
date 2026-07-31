<script setup lang="ts">
import { ref } from 'vue'
import { Save, Eye, EyeOff } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

const token = ref(authStore.apiToken)
const showToken = ref(false)
const saved = ref(false)

function handleSave() {
  authStore.setToken(token.value)
  saved.value = true
  setTimeout(() => { saved.value = false }, 2000)
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <h3 class="text-sm font-medium text-text-primary mb-4">API Token</h3>

    <div class="flex gap-2">
      <div class="relative flex-1">
        <input
          v-model="token"
          :type="showToken ? 'text' : 'password'"
          class="w-full bg-white/5 border border-white/5 rounded-lg pl-3 pr-10 py-2 text-sm text-text-primary placeholder:text-text-muted focus:border-blue-500/50 focus:outline-none"
          placeholder="输入 API Token"
        />
        <button
          class="absolute right-2 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-primary transition-colors"
          @click="showToken = !showToken"
        >
          <EyeOff v-if="showToken" class="w-4 h-4" />
          <Eye v-else class="w-4 h-4" />
        </button>
      </div>
      <button
        class="flex items-center gap-1.5 px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-500 transition-colors disabled:opacity-50"
        :disabled="!token"
        @click="handleSave"
      >
        <Save class="w-4 h-4" />
        {{ saved ? '已保存' : '保存' }}
      </button>
    </div>
    <p v-if="saved" class="text-xs text-accent-green mt-2">Token 已保存成功</p>
  </div>
</template>
