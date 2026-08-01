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

const periods = [
  { key: '7d' as const, label: '近 7 天' },
  { key: '30d' as const, label: '近 30 天' },
  { key: '90d' as const, label: '近 90 天' },
]
</script>

<template>
  <div class="dashboard">
    <h1 class="page-title">仪表盘</h1>

    <div class="period-bar">
      <button
        v-for="p in periods"
        :key="p.key"
        :class="['period-btn', { active: dashboard.period === p.key }]"
        @click="dashboard.setPeriod(p.key)"
      >
        {{ p.label }}
      </button>
    </div>

    <div v-if="dashboard.isLoading" class="state-msg">加载中...</div>
    <div v-else-if="dashboard.loadError" class="state-msg" style="color: #FF453A;">
      {{ dashboard.loadError }}
    </div>

    <template v-else>
      <div class="stat-grid">
        <div class="stat-card">
          <div class="stat-label">智能体总数</div>
          <div class="stat-value" style="color: #5AC8FA;">{{ dashboard.totalAgents }}</div>
          <div v-if="dashboard.agentStats.length" class="stat-trend" style="color: #30D158;">
            {{ dashboard.agentStats.length }} 活跃
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-label">活跃会话</div>
          <div class="stat-value" style="color: #30D158;">{{ dashboard.activeSessions }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Token 消耗</div>
          <div class="stat-value" style="color: #FFD60A;">{{ dashboard.totalTokens.toLocaleString() }}</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">累计费用</div>
          <div class="stat-value" style="color: #FF9F0A;">
            ¥{{ dashboard.totalCost.toFixed(1) }}
          </div>
        </div>
      </div>

      <div class="chart-row">
        <div class="chart-card">
          <div class="card-title">Token 用量趋势</div>
          <div class="chart-placeholder">图表加载中...</div>
        </div>
        <div class="chart-card">
          <div class="card-title">系统健康</div>
          <div class="health-bars">
            <div class="health-row">
              <span class="health-label">CPU</span>
              <div class="health-track"><div class="health-fill" style="width: 32%; background: #30D158;"></div></div>
              <span class="health-val">32%</span>
            </div>
            <div class="health-row">
              <span class="health-label">内存</span>
              <div class="health-track"><div class="health-fill" style="width: 67%; background: #FFD60A;"></div></div>
              <span class="health-val">67%</span>
            </div>
            <div class="health-row">
              <span class="health-label">磁盘</span>
              <div class="health-track"><div class="health-fill" style="width: 45%; background: #30D158;"></div></div>
              <span class="health-val">45%</span>
            </div>
          </div>
        </div>
      </div>

      <div class="bottom-row">
        <div class="chart-card">
          <div class="card-title">智能体状态</div>
          <div class="agent-status-list">
            <div
              v-for="stat in dashboard.agentStats"
              :key="stat.agentId"
              class="agent-status-row"
            >
              <span class="agent-name">{{ stat.agentName || stat.agentId }}</span>
              <span class="agent-sessions" style="color: #5AC8FA;">{{ stat.sessionCount }} 会话</span>
            </div>
            <div v-if="!dashboard.agentStats.length" class="chart-placeholder">
              暂无数据
            </div>
          </div>
        </div>
        <div class="chart-card">
          <div class="card-title">最近会话</div>
          <div class="chart-placeholder">暂无最近会话</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.dashboard {
  padding: 20px;
}

.page-title {
  font-size: 28px;
  font-weight: 700;
  color: #F5F5F7;
  margin: 0 0 20px;
}

.period-bar {
  display: flex;
  gap: 2px;
  background: rgba(44, 44, 46, 0.5);
  border-radius: 10px;
  padding: 3px;
  width: fit-content;
  margin-bottom: 20px;
}

.period-btn {
  padding: 5px 14px;
  border-radius: 8px;
  border: none;
  font-size: 12px;
  color: #98989D;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
}

.period-btn.active {
  background: rgba(90, 200, 250, 0.12);
  color: #F5F5F7;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  padding: 16px;
}

.stat-label {
  font-size: 11px;
  color: #98989D;
  margin-bottom: 6px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
}

.stat-trend {
  font-size: 11px;
  margin-top: 4px;
}

.chart-row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
}

.bottom-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.chart-card {
  background: rgba(44, 44, 46, 0.5);
  border-radius: 14px;
  padding: 16px;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
  color: #F5F5F7;
  margin-bottom: 12px;
}

.chart-placeholder {
  font-size: 12px;
  color: #636366;
  text-align: center;
  padding: 24px 0;
}

.state-msg {
  text-align: center;
  padding: 48px 0;
  font-size: 14px;
  color: #98989D;
}

.health-bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.health-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.health-label {
  font-size: 11px;
  color: #98989D;
  width: 28px;
}

.health-track {
  flex: 1;
  height: 4px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 2px;
  overflow: hidden;
}

.health-fill {
  height: 100%;
  border-radius: 2px;
}

.health-val {
  font-size: 11px;
  color: #98989D;
  width: 28px;
  text-align: right;
}

.agent-status-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.agent-status-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.agent-name {
  font-size: 13px;
  color: #F5F5F7;
}

.agent-sessions {
  font-size: 12px;
  font-weight: 500;
}
</style>
