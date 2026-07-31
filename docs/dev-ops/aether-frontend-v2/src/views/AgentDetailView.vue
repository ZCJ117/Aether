<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'

const route = useRoute()
const router = useRouter()
const agentStore = useAgentStore()

const activeTab = ref<'basic' | 'config' | 'tools' | 'permissions'>('basic')

const agentId = computed(() => route.params.id as string)

const agent = computed(() =>
  agentStore.managementAgents.find((a) => a.agentId === agentId.value)
)

onMounted(() => {
  if (agentStore.managementAgents.length === 0) {
    agentStore.loadManagementAgents()
  }
})

function goBack() {
  router.push({ name: 'AgentManagement' })
}
</script>

<template>
  <div class="agent-detail">
    <div class="page-header">
      <button class="btn-back" @click="goBack">&larr; 返回</button>
      <h1>{{ agent?.agentName || agentId }}</h1>
    </div>

    <div v-if="!agent" class="loading">加载中...</div>

    <template v-else>
      <!-- Tabs -->
      <div class="tabs">
        <button
          v-for="tab in (['basic', 'config', 'tools', 'permissions'] as const)"
          :key="tab"
          :class="['tab', { active: activeTab === tab }]"
          @click="activeTab = tab"
        >
          {{
            tab === 'basic' ? '基本信息' :
            tab === 'config' ? '配置' :
            tab === 'tools' ? '工具' :
            '权限'
          }}
        </button>
      </div>

      <!-- Tab Content -->
      <div class="tab-content">
        <!-- Basic Info -->
        <div v-if="activeTab === 'basic'" class="detail-grid">
          <div class="detail-item">
            <label>智能体 ID</label>
            <span>{{ agent.agentId }}</span>
          </div>
          <div class="detail-item">
            <label>名称</label>
            <span>{{ agent.agentName || '-' }}</span>
          </div>
          <div class="detail-item">
            <label>类型</label>
            <span>{{ agent.agentType || '-' }}</span>
          </div>
          <div class="detail-item">
            <label>状态</label>
            <span>{{ agent.status === 'active' ? '运行中' : '已停用' }}</span>
          </div>
          <div class="detail-item">
            <label>系统指令</label>
            <span class="multiline">{{ agent.instruction || '未设置' }}</span>
          </div>
          <div class="detail-item">
            <label>模型</label>
            <span>{{ agent.modelName || agent.effectiveModelId || '-' }}</span>
          </div>
        </div>

        <!-- Config -->
        <div v-if="activeTab === 'config'" class="detail-grid">
          <div class="detail-item">
            <label>有效模型 ID</label>
            <span>{{ agent.effectiveModelId || '-' }}</span>
          </div>
          <div class="detail-item">
            <label>温度</label>
            <span>{{ agent.floatTemperature ?? '-' }}</span>
          </div>
          <div class="detail-item">
            <label>Top-P</label>
            <span>{{ agent.floatTopP ?? '-' }}</span>
          </div>
          <div class="detail-item">
            <label>最大 Token</label>
            <span>{{ agent.intMaxOutputTokens ?? '-' }}</span>
          </div>
          <div class="detail-item">
            <label>最大推理轮次</label>
            <span>{{ agent.maxTurnsPerInteraction ?? '-' }}</span>
          </div>
          <div class="detail-item">
            <label>Token 预算</label>
            <span>{{ agent.intTokenBudget ?? '-' }}</span>
          </div>
        </div>

        <!-- Tools -->
        <div v-if="activeTab === 'tools'" class="detail-grid">
          <div v-if="!agent.tools || agent.tools.length === 0" class="empty-tab">
            没有已注册的工具
          </div>
          <div v-else class="tools-list">
            <div v-for="tool in agent.tools" :key="tool" class="tool-item">
              {{ tool }}
            </div>
          </div>
        </div>

        <!-- Permissions -->
        <div v-if="activeTab === 'permissions'" class="detail-grid">
          <div class="detail-item">
            <label>自动授予工具权限</label>
            <span>{{ agent.autoGrantToolPermissions ? '是' : '否' }}</span>
          </div>
          <div class="detail-item">
            <label>允许所有工具</label>
            <span>{{ agent.allowAllTools ? '是' : '否' }}</span>
          </div>
          <div class="detail-item">
            <label>允许写入操作</label>
            <span>{{ agent.allowWriteOperations ? '是' : '否' }}</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.agent-detail {
  padding: 1.5rem;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.btn-back {
  padding: 0.375rem 0.75rem;
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary, #aaa);
  font-size: 0.8125rem;
  cursor: pointer;
  transition: color 0.2s;
}

.btn-back:hover {
  color: var(--text-primary, #eee);
}

.page-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.loading {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary, #888);
}

.tabs {
  display: flex;
  gap: 0;
  border-bottom: 1px solid var(--border-color, #2a2a4a);
  margin-bottom: 1.5rem;
}

.tab {
  padding: 0.625rem 1.25rem;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--text-secondary, #888);
  font-size: 0.875rem;
  cursor: pointer;
  transition: all 0.2s;
}

.tab.active {
  color: var(--accent-color, #6366f1);
  border-bottom-color: var(--accent-color, #6366f1);
}

.tab:hover:not(.active) {
  color: var(--text-primary, #eee);
}

.tab-content {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.5rem;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 1.25rem;
}

.detail-item {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.detail-item label {
  font-size: 0.75rem;
  color: var(--text-secondary, #888);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.detail-item span {
  font-size: 0.9375rem;
  color: var(--text-primary, #eee);
}

.detail-item .multiline {
  white-space: pre-wrap;
  font-size: 0.875rem;
  line-height: 1.5;
}

.empty-tab {
  grid-column: span 2;
  text-align: center;
  padding: 2rem;
  color: var(--text-secondary, #888);
}

.tools-list {
  grid-column: span 2;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.tool-item {
  padding: 0.375rem 0.75rem;
  background: var(--bg-primary, #0f0f1a);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 6px;
  font-size: 0.8125rem;
  color: var(--text-primary, #eee);
  font-family: monospace;
}
</style>
