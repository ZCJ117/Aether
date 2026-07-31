<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useWorkflowStore } from '@/stores/workflow'
import FutureVersionBanner from '@/components/common/FutureVersionBanner.vue'

const router = useRouter()
const workflowStore = useWorkflowStore()

onMounted(() => {
  workflowStore.loadMockWorkflows()
})

function handleCreate() {
  router.push({ name: 'WorkflowEditor' })
}

function handleEdit(id: string) {
  router.push({ name: 'WorkflowEditor', params: { id } })
}

function handleDelete(id: string) {
  if (confirm('确定要删除该工作流吗？')) {
    workflowStore.deleteWorkflow(id)
  }
}
</script>

<template>
  <div class="workflow-list">
    <div class="page-header">
      <h1>工作流</h1>
      <button class="btn-create" @click="handleCreate">+ 创建工作流</button>
    </div>

    <FutureVersionBanner />

    <div v-if="workflowStore.workflows.length === 0" class="empty">
      <p>暂无工作流，点击"创建工作流"开始</p>
    </div>

    <div v-else class="workflow-grid">
      <div
        v-for="wf in workflowStore.workflows"
        :key="wf.id"
        class="workflow-card"
        @click="handleEdit(wf.id)"
      >
        <h3>{{ wf.name }}</h3>
        <p>{{ wf.description }}</p>
        <div class="workflow-meta">
          <span>{{ wf.nodeCount }} 节点</span>
          <span>{{ wf.edgeCount }} 连线</span>
          <span>{{ new Date(wf.updatedAt).toLocaleDateString() }}</span>
        </div>
        <div class="workflow-actions" @click.stop>
          <button class="btn-edit" @click="handleEdit(wf.id)">编辑</button>
          <button class="btn-delete" @click="handleDelete(wf.id)">删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workflow-list {
  padding: 1.5rem;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.page-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.btn-create {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 8px;
  background: var(--accent-color, #6366f1);
  color: #fff;
  font-size: 0.875rem;
  cursor: pointer;
  transition: opacity 0.2s;
}

.btn-create:hover {
  opacity: 0.9;
}

.empty {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary, #888);
}

.workflow-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 1rem;
}

.workflow-card {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.25rem;
  cursor: pointer;
  transition: border-color 0.2s, transform 0.15s;
}

.workflow-card:hover {
  border-color: rgba(99, 102, 241, 0.4);
  transform: translateY(-1px);
}

.workflow-card h3 {
  margin: 0 0 0.5rem;
  font-size: 1rem;
  color: var(--text-primary, #eee);
}

.workflow-card p {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
  color: var(--text-secondary, #aaa);
  line-height: 1.4;
}

.workflow-meta {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary, #666);
  margin-bottom: 0.75rem;
}

.workflow-actions {
  display: flex;
  gap: 0.5rem;
  padding-top: 0.75rem;
  border-top: 1px solid var(--border-color, #2a2a4a);
}

.btn-edit, .btn-delete {
  padding: 0.375rem 0.75rem;
  border-radius: 6px;
  font-size: 0.75rem;
  cursor: pointer;
  transition: opacity 0.2s;
  background: transparent;
  border: 1px solid var(--border-color, #2a2a4a);
}

.btn-edit {
  color: var(--accent-color, #6366f1);
  border-color: rgba(99, 102, 241, 0.3);
}

.btn-delete {
  color: #ef4444;
  border-color: rgba(239, 68, 68, 0.3);
}

.btn-edit:hover, .btn-delete:hover {
  opacity: 0.8;
}
</style>
