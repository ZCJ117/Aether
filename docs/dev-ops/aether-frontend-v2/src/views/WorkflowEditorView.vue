<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkflowStore } from '@/stores/workflow'

const route = useRoute()
const workflowStore = useWorkflowStore()

onMounted(() => {
  const id = route.params.id as string | undefined
  if (id) {
    // In Phase 7, load the workflow by ID
  } else {
    workflowStore.newWorkflow()
  }
})
</script>

<template>
  <div class="workflow-editor">
    <div class="editor-header">
      <h1>DAG 工作流编辑器</h1>
      <span class="workflow-name">{{ workflowStore.workflowName }}</span>
    </div>

    <div class="editor-canvas">
      <div class="canvas-placeholder">
        <div class="placeholder-content">
          <div class="placeholder-icon">🔧</div>
          <h2>DAG 工作流编辑器</h2>
          <p>拖拽节点到画布上，构建你的智能体编排工作流</p>
          <div class="placeholder-stats">
            <span v-if="workflowStore.nodeCount > 0">
              节点: {{ workflowStore.nodeCount }} | 连线: {{ workflowStore.edges.length }}
            </span>
            <span v-else>画布为空，开始添加节点吧</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="workflowStore.isDirty" class="dirty-indicator">
      未保存的更改
    </div>
  </div>
</template>

<style scoped>
.workflow-editor {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--header-height, 56px));
}

.editor-header {
  display: flex;
  align-items: center;
  gap: 1rem;
  padding: 0.75rem 1.5rem;
  border-bottom: 1px solid var(--border-color, #2a2a4a);
  background: var(--bg-secondary, #1a1a2e);
}

.editor-header h1 {
  font-size: 1.125rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.workflow-name {
  font-size: 0.8125rem;
  color: var(--text-secondary, #888);
}

.editor-canvas {
  flex: 1;
  background: var(--bg-primary, #0f0f1a);
  position: relative;
  overflow: hidden;
}

.canvas-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-content {
  text-align: center;
  color: var(--text-secondary, #666);
}

.placeholder-icon {
  font-size: 3rem;
  margin-bottom: 0.75rem;
}

.placeholder-content h2 {
  margin: 0 0 0.5rem;
  font-size: 1.25rem;
  color: var(--text-primary, #eee);
}

.placeholder-content p {
  margin: 0 0 1rem;
  font-size: 0.875rem;
}

.placeholder-stats {
  font-size: 0.8125rem;
  color: var(--accent-color, #6366f1);
}

.dirty-indicator {
  position: fixed;
  bottom: 1rem;
  right: 1rem;
  padding: 0.375rem 0.75rem;
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: 6px;
  font-size: 0.75rem;
  color: #f59e0b;
}
</style>
