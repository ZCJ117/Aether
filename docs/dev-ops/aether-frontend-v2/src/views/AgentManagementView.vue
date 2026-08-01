<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'

const router = useRouter()
const agentStore = useAgentStore()

onMounted(() => {
  agentStore.loadManagementAgents()
})

function handleEdit(id: string) {
  router.push(`/app/agents/${id}`)
}

function handleDelete(id: string) {
  if (confirm('确定要删除该智能体吗？')) {
    agentStore.removeManagementAgent(id)
  }
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">智能体</h1>

    <div v-if="agentStore.managementLoading" class="state-msg">加载中...</div>

    <template v-else>
      <div class="list-card">
        <div class="table-header">
          <span>名称</span><span>类型</span><span>模型</span><span>状态</span>
        </div>

        <div v-if="agentStore.managementAgents.length === 0" class="state-msg">
          暂无智能体
        </div>

        <div
          v-for="agent in agentStore.managementAgents"
          :key="agent.agentId"
          class="table-row"
        >
          <div class="cell-name">
            <div class="cell-title">{{ agent.agentName || agent.agentId }}</div>
            <div class="cell-desc">{{ agent.agentDesc || '—' }}</div>
          </div>
          <span class="cell-text">{{ agent.agentType || 'ReActAgent' }}</span>
          <span class="cell-text">{{ agent.modelName || agent.modelRef || '—' }}</span>
          <span :class="['status-dot', agent.status === 'active' ? 'on' : 'off']">
            {{ agent.status === 'active' ? '活跃' : '已暂停' }}
          </span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 20px; }
.page-title { font-size: 28px; font-weight: 700; color: #F5F5F7; margin: 0 0 18px; }

.list-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  overflow: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  padding: 10px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.05);
  font-size: 11px;
  color: #636366;
  text-transform: uppercase;
  letter-spacing: 0.3px;
}

.table-row {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  padding: 12px 16px;
  border-bottom: 0.5px solid rgba(255, 255, 255, 0.04);
  align-items: center;
}

.table-row:last-child { border-bottom: none; }

.cell-title { font-size: 14px; font-weight: 600; color: #F5F5F7; }
.cell-desc { font-size: 11px; color: #636366; margin-top: 2px; }
.cell-text { font-size: 12px; color: #98989D; }

.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.status-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-dot.on { color: #30D158; }
.status-dot.on::before { background: #30D158; }
.status-dot.off { color: #FF9F0A; }
.status-dot.off::before { background: #FF9F0A; }

.state-msg {
  text-align: center;
  padding: 32px 0;
  font-size: 13px;
  color: #98989D;
}
</style>
