<script setup lang="ts">
import { ref } from 'vue'

interface RecentSession {
  id: string
  title: string
  agent: string
  time: string
  status: 'ACTIVE' | 'ARCHIVED' | 'ERROR'
}

const sessions = ref<RecentSession[]>([
  { id: 's001', title: '规划东京三日行程', agent: '旅游规划智能体', time: '10 分钟前', status: 'ACTIVE' },
  { id: 's002', title: '审查支付模块代码', agent: '代码审查助手', time: '25 分钟前', status: 'ACTIVE' },
  { id: 's003', title: '生成 Q2 分析报告', agent: '数据分析智能体', time: '1 小时前', status: 'ARCHIVED' },
  { id: 's004', title: '查询天气接口文档', agent: 'API 助手', time: '2 小时前', status: 'ERROR' },
  { id: 's005', title: '优化数据库查询', agent: '代码审查助手', time: '3 小时前', status: 'ARCHIVED' },
])

const statusConfig: Record<string, { label: string; cls: string }> = {
  ACTIVE: { label: '进行中', cls: 'text-accent-green bg-accent-green/10' },
  ARCHIVED: { label: '已归档', cls: 'text-slate-400 bg-slate-400/10' },
  ERROR: { label: '异常', cls: 'text-accent-red bg-accent-red/10' },
}
</script>

<template>
  <div class="bg-surface-card border border-white/5 rounded-xl p-5">
    <div class="flex items-center justify-between mb-4">
      <h3 class="text-sm font-medium text-text-primary">最近会话</h3>
      <button class="text-xs text-blue-400 hover:text-blue-300 transition-colors">查看全部</button>
    </div>

    <div class="overflow-x-auto">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-white/5">
            <th class="text-left py-2 text-xs font-medium text-text-muted">会话标题</th>
            <th class="text-left py-2 text-xs font-medium text-text-muted">智能体</th>
            <th class="text-left py-2 text-xs font-medium text-text-muted">时间</th>
            <th class="text-left py-2 text-xs font-medium text-text-muted">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="s in sessions"
            :key="s.id"
            class="border-b border-white/[0.02] hover:bg-white/[0.02] transition-colors"
          >
            <td class="py-2.5 text-text-primary truncate max-w-[180px]">{{ s.title }}</td>
            <td class="py-2.5 text-text-secondary">{{ s.agent }}</td>
            <td class="py-2.5 text-text-muted text-xs">{{ s.time }}</td>
            <td class="py-2.5">
              <span
                :class="statusConfig[s.status]?.cls"
                class="inline-flex items-center px-1.5 py-0.5 rounded text-2xs"
              >
                {{ statusConfig[s.status]?.label }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
