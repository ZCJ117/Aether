<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAgentStore } from '@/stores/agent'
import { useMemoryStore } from '@/stores/memory'
import { LogOut } from 'lucide-vue-next'
import type { LongTermMemoryEntry } from '@/types/memory'

const router = useRouter()
const auth = useAuthStore()
const agentStore = useAgentStore()
const memoryStore = useMemoryStore()

const apiTokenInput = ref(auth.apiToken)
const connectionUrl = ref('http://localhost:8080')

const editingId = ref<string | null>(null)
const editingContent = ref('')
const isAdding = ref(false)
const newContent = ref('')

const maskedToken = computed(() => {
  const t = auth.apiToken
  if (t.length <= 8) return t
  return t.slice(0, 4) + '****' + t.slice(-4)
})

const currentAgentName = computed(() => {
  return agentStore.selectedAgent?.agentName || agentStore.selectedAgent?.agentId || ''
})

watch(
  () => agentStore.selectedAgentId,
  (newId) => {
    memoryStore.loadMemories(newId)
    cancelEdit()
  },
  { immediate: true }
)

function handleSaveToken() {
  auth.setToken(apiTokenInput.value)
}

function handleLogout() {
  auth.logout()
  router.push('/')
}

function startEdit(entry: LongTermMemoryEntry) {
  editingId.value = entry.id
  editingContent.value = entry.content
}

function cancelEdit() {
  editingId.value = null
  editingContent.value = ''
  isAdding.value = false
  newContent.value = ''
}

function saveEdit() {
  if (editingId.value && editingContent.value.trim()) {
    memoryStore.updateMemory(editingId.value, editingContent.value.trim())
  }
  cancelEdit()
}

function handleDelete(id: string) {
  memoryStore.deleteMemory(id)
  if (editingId.value === id) cancelEdit()
}

function startAdd() {
  isAdding.value = true
  newContent.value = ''
  editingId.value = null
}

function confirmAdd() {
  if (newContent.value.trim()) {
    memoryStore.addMemory(newContent.value.trim())
  }
  cancelEdit()
}

function summary(content: string): string {
  return content.length > 80 ? content.slice(0, 80) + '…' : content
}

function formatTime(ts: number): string {
  return new Date(ts).toLocaleString()
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">设置</h1>

    <div class="groups">
      <!-- API -->
      <div class="group-label">API</div>
      <div class="group-card">
        <div class="group-row">
          <span class="row-label">当前 Token</span>
          <span v-if="auth.isValidToken" class="row-value">{{ maskedToken }}</span>
          <span v-else class="row-value" style="color: #FF453A;">未设置</span>
        </div>
        <div class="group-row">
          <span class="row-label">新 Token</span>
          <input v-model="apiTokenInput" type="password" placeholder="输入新 Token" class="row-input" />
        </div>
        <div class="group-row" style="justify-content: flex-end;">
          <button class="action-btn" @click="handleSaveToken">保存</button>
        </div>
        <div class="group-row">
          <span class="row-label">连接地址</span>
          <span class="row-value">{{ connectionUrl }}</span>
        </div>
      </div>

      <!-- Long-term Memory -->
      <div class="group-label">
        长期记忆
        <span v-if="currentAgentName" class="agent-tag"> · 当前：{{ currentAgentName }}</span>
      </div>
      <div class="group-card">
        <div v-if="!agentStore.selectedAgentId" class="empty-msg">
          请先在对话页面中选择一个智能体
        </div>

        <template v-else>
          <div v-if="memoryStore.memories.length === 0 && !isAdding" class="empty-msg">
            该 Agent 暂无长期记忆
          </div>

          <div
            v-for="entry in memoryStore.memories"
            :key="entry.id"
            class="group-row"
            style="flex-direction: column; align-items: stretch;"
          >
            <div v-if="editingId !== entry.id" class="mem-row">
              <div class="mem-content">
                <div class="mem-text">{{ summary(entry.content) }}</div>
                <div class="mem-time">{{ formatTime(entry.updatedAt) }}</div>
              </div>
              <div class="mem-actions">
                <span class="edit-link" @click="startEdit(entry)">编辑</span>
                <span class="del-link" @click="handleDelete(entry.id)">删除</span>
              </div>
            </div>
            <div v-else class="mem-edit">
              <textarea v-model="editingContent" class="mem-textarea" rows="3" />
              <div class="mem-edit-btns">
                <button class="action-btn" @click="saveEdit">保存</button>
                <button class="cancel-btn" @click="cancelEdit">取消</button>
              </div>
            </div>
          </div>

          <div v-if="isAdding" class="group-row" style="flex-direction: column; align-items: stretch;">
            <div class="mem-edit">
              <textarea v-model="newContent" class="mem-textarea" rows="3" placeholder="输入新的长期记忆…" />
              <div class="mem-edit-btns">
                <button class="action-btn" @click="confirmAdd">添加</button>
                <button class="cancel-btn" @click="cancelEdit">取消</button>
              </div>
            </div>
          </div>

          <div v-if="!isAdding" class="group-row" style="justify-content: center;">
            <span class="add-link" @click="startAdd">+ 添加长期记忆</span>
          </div>
        </template>
      </div>

      <!-- Account -->
      <div class="group-label">账户</div>
      <div class="group-card">
        <div class="group-row" style="justify-content: center;">
          <span class="logout-link" @click="handleLogout">退出登录</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 20px; max-width: 560px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 20px; }

.groups { display: flex; flex-direction: column; gap: 6px; }

.group-label {
  font-size: 11px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 12px 0 6px;
}
.group-label:first-child { padding-top: 0; }

.agent-tag { text-transform: none; letter-spacing: 0; color: #5AC8FA; }

.group-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  overflow: hidden;
}

.group-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.04);
}
.group-row:last-child { border-bottom: none; }

.row-label { font-size: 14px; color: #F5F5F7; }
.row-value { font-size: 13px; color: #98989D; }

.row-input {
  width: 180px;
  padding: 6px 10px;
  border-radius: 7px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(0, 0, 0, 0.3);
  color: #F5F5F7;
  font-size: 13px;
  text-align: right;
  outline: none;
  font-family: inherit;
}
.row-input:focus { border-color: #5AC8FA; }

.action-btn {
  padding: 5px 14px;
  border-radius: 7px;
  border: none;
  background: #5AC8FA;
  color: #000;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  font-family: inherit;
}

.empty-msg {
  text-align: center;
  padding: 24px 16px;
  font-size: 13px;
  color: #636366;
}

.mem-row { display: flex; align-items: center; gap: 12px; width: 100%; }
.mem-content { flex: 1; min-width: 0; }
.mem-text { font-size: 13px; color: #F5F5F7; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mem-time { font-size: 10px; color: #636366; margin-top: 2px; }
.mem-actions { display: flex; gap: 10px; flex-shrink: 0; }

.edit-link { font-size: 11px; color: #5AC8FA; cursor: pointer; }
.del-link { font-size: 11px; color: #FF453A; cursor: pointer; }
.add-link { font-size: 13px; color: #5AC8FA; cursor: pointer; }
.logout-link { font-size: 14px; color: #FF453A; cursor: pointer; }

.mem-edit { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.mem-textarea {
  width: 100%;
  padding: 8px 10px;
  border-radius: 8px;
  border: 0.5px solid rgba(255, 255, 255, 0.08);
  background: rgba(0, 0, 0, 0.3);
  color: #F5F5F7;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.5;
  outline: none;
  resize: vertical;
}
.mem-textarea:focus { border-color: #5AC8FA; }
.mem-edit-btns { display: flex; gap: 8px; justify-content: flex-end; }

.cancel-btn {
  padding: 5px 14px;
  border-radius: 7px;
  border: 0.5px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  color: #98989D;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
</style>
