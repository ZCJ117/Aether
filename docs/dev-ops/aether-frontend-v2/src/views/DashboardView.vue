<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { useDashboardStore } from '@/stores/dashboard'

const dashboard = useDashboardStore()

onMounted(() => {
  dashboard.loadStats()
  dashboard.startAutoRefresh(30000)
})

onUnmounted(() => {
  dashboard.stopAutoRefresh()
})

const periods: Array<'7d' | '30d' | '90d'> = ['7d', '30d', '90d']
</script>

<template>
  <div class="dashboard">
    <div class="dashboard-header">
      <h1>仪表盘</h1>
      <div class="period-selector">
        <button
          v-for="p in periods"
          :key="p"
          :class="['period-btn', { active: dashboard.period === p }]"
          @click="dashboard.setPeriod(p)"
        >
          {{ p }}
        </button>
      </div>
    </div>

    <!-- Loading -->
    <div v-if="dashboard.isLoading" class="loading-state">加载中...</div>

    <!-- Error -->
    <div v-else-if="dashboard.loadError" class="error-state">
      <p>数据加载失败</p>
      <p class="error-detail">{{ dashboard.loadError }}</p>
    </div>

    <!-- Content -->
    <template v-else>
    <!-- Stat Cards -->
    <div class="stat-grid">
      <div class="stat-card">
        <div class="stat-label">活跃智能体</div>
        <div class="stat-value">{{ dashboard.totalAgents }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">活跃会话</div>
        <div class="stat-value">{{ dashboard.activeSessions }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">消耗 Token</div>
        <div class="stat-value">{{ dashboard.totalTokens.toLocaleString() }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">预估费用</div>
        <div class="stat-value">${{ dashboard.totalCost.toFixed(2) }}</div>
      </div>
    </div>

    <!-- Charts & Agent Status -->
    <div class="dashboard-content">
      <div class="chart-section">
        <div class="chart-area">
          <h3>Token 用量趋势</h3>
          <div class="chart-placeholder">
            <span>图表区域 — Token 用量 ({{ dashboard.period }})</span>
          </div>
        </div>
        <div class="chart-area">
          <h3>会话活跃度</h3>
          <div class="chart-placeholder">
            <span>图表区域 — 会话活跃度 ({{ dashboard.sessionActivity.length }} 条记录)</span>
          </div>
        </div>
      </div>
      <div class="agent-section">
        <h3>智能体状态</h3>
        <div class="agent-list">
          <div
            v-for="agent in dashboard.agentStats"
            :key="agent.agentId"
            class="agent-item"
          >
            <div class="agent-info">
              <span :class="['agent-status-dot', agent.status]"></span>
              <span class="agent-name">{{ agent.agentName }}</span>
            </div>
            <div class="agent-stats">
              <span>{{ agent.sessionCount }} 会话</span>
              <span>{{ agent.tokenCount.toLocaleString() }} Token</span>
            </div>
          </div>
        </div>

        <div class="system-health">
          <h4>系统健康</h4>
          <div class="health-metrics">
            <div class="health-item">
              <span>CPU</span>
              <span>{{ dashboard.systemHealth.cpuPercent }}%</span>
            </div>
            <div class="health-item">
              <span>内存</span>
              <span>{{ dashboard.systemHealth.memoryPercent }}%</span>
            </div>
            <div class="health-item">
              <span>磁盘</span>
              <span>{{ dashboard.systemHealth.diskPercent }}%</span>
            </div>
            <div class="health-item">
              <span>TPS</span>
              <span>{{ dashboard.systemHealth.tps }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    </template>
  </div>
</template>

<style scoped>
.loading-state, .error-state {
  text-align: center;
  padding: 3rem;
  color: var(--text-secondary, #888);
}
.error-detail {
  font-size: 0.75rem;
  margin-top: 0.5rem;
  opacity: 0.6;
}

.dashboard {
  padding: 1.5rem;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 1.5rem;
}

.dashboard-header h1 {
  font-size: 1.5rem;
  margin: 0;
  color: var(--text-primary, #eee);
}

.period-selector {
  display: flex;
  gap: 0.25rem;
  background: var(--bg-secondary, #1a1a2e);
  border-radius: 8px;
  padding: 2px;
}

.period-btn {
  padding: 0.375rem 0.875rem;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary, #888);
  font-size: 0.8125rem;
  cursor: pointer;
  transition: all 0.2s;
}

.period-btn.active {
  background: var(--accent-color, #6366f1);
  color: #fff;
}

.period-btn:hover:not(.active) {
  color: var(--text-primary, #eee);
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1rem;
  margin-bottom: 1.5rem;
}

.stat-card {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.25rem;
}

.stat-label {
  font-size: 0.8125rem;
  color: var(--text-secondary, #888);
  margin-bottom: 0.5rem;
}

.stat-value {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--text-primary, #eee);
}

.dashboard-content {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 1rem;
}

.chart-section {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.chart-area {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.25rem;
}

.chart-area h3 {
  margin: 0 0 1rem;
  font-size: 0.9375rem;
  color: var(--text-primary, #eee);
}

.chart-placeholder {
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--border-color, #2a2a4a);
  border-radius: 8px;
  color: var(--text-secondary, #666);
  font-size: 0.875rem;
}

.agent-section {
  background: var(--bg-secondary, #1a1a2e);
  border: 1px solid var(--border-color, #2a2a4a);
  border-radius: 10px;
  padding: 1.25rem;
}

.agent-section h3 {
  margin: 0 0 1rem;
  font-size: 0.9375rem;
  color: var(--text-primary, #eee);
}

.agent-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.agent-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.75rem;
  background: var(--bg-primary, #0f0f1a);
  border-radius: 8px;
}

.agent-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.agent-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.agent-status-dot.active {
  background: #22c55e;
}

.agent-status-dot.idle {
  background: #f59e0b;
}

.agent-status-dot.error {
  background: #ef4444;
}

.agent-name {
  font-size: 0.875rem;
  color: var(--text-primary, #eee);
}

.agent-stats {
  display: flex;
  gap: 1rem;
  font-size: 0.75rem;
  color: var(--text-secondary, #888);
}

.system-health {
  border-top: 1px solid var(--border-color, #2a2a4a);
  padding-top: 1rem;
}

.system-health h4 {
  margin: 0 0 0.75rem;
  font-size: 0.8125rem;
  color: var(--text-secondary, #aaa);
}

.health-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.5rem;
}

.health-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  padding: 0.5rem;
  background: var(--bg-primary, #0f0f1a);
  border-radius: 6px;
  font-size: 0.75rem;
  color: var(--text-secondary, #888);
}

.health-item span:last-child {
  font-weight: 600;
  color: var(--text-primary, #eee);
}
</style>
