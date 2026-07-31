<script setup lang="ts">
import { ref } from 'vue'
import {
  Sparkles, Terminal, Activity, Wrench, FileText, Archive, Trash2,
  Search, Play, CheckCircle, MoreHorizontal,
} from 'lucide-vue-next'

type AgentTask = {
  id: string
  agent: string
  task: string
  status: string
  time: string
  active?: boolean
}

const TASKS = ref<AgentTask[]>([
  { id: '1', agent: 'CodeAgent', task: '代码审查与重构 · 3 文件已分析', status: 'completed', time: '9:41 AM', active: true },
  { id: '2', agent: 'DataAgent', task: '数据管道 ETL · Q3 报表生成中', status: 'running', time: '8:12 AM' },
  { id: '3', agent: 'DocAgent', task: 'PRD 文档生成 · v2.1 已发布', status: 'completed', time: 'Yesterday' },
  { id: '4', agent: 'OpsAgent', task: '集群健康检查 · 所有节点正常', status: 'completed', time: 'Yesterday' },
  { id: '5', agent: 'TestAgent', task: '回归测试套件 · 142/142 通过', status: 'completed', time: 'Mon' },
  { id: '6', agent: 'MailAgent', task: '邮件摘要 · 12 封待处理', status: 'pending', time: 'Mon' },
])

const SIDEBAR_ITEMS = [
  { icon: Terminal, label: 'Agent 控制台', count: 6, active: true },
  { icon: Activity, label: '运行中', count: 1 },
  { icon: CheckCircle, label: '已完成' },
  { icon: FileText, label: '日志', count: 24 },
  { icon: Archive, label: '归档' },
  { icon: Trash2, label: '回收站' },
]

const LABELS = [
  { name: '生产环境', color: '#00d2ff' },
  { name: '开发环境', color: '#A4F4FD' },
  { name: '测试环境', color: '#f59e0b' },
  { name: '沙箱环境', color: '#10b981' },
]

const activeTask = ref(TASKS.value[0])
</script>

<template>
  <section class="max-w-6xl mx-auto px-6 py-16 md:py-24">
    <div
      v-motion
      :initial="{ opacity: 0, y: 40 }"
      :enter="{ opacity: 1, y: 0, transition: { delay: 1100, duration: 900, ease: [0.22, 1, 0.36, 1] } }"
      class="relative rounded-2xl overflow-hidden border border-white/10 bg-[#0e1014]/90 backdrop-blur-2xl"
    >
      <!-- Title bar -->
      <div class="flex items-center justify-between px-4 h-9 bg-black/40 border-b border-white/10">
        <div class="flex items-center gap-2">
          <span class="w-3 h-3 rounded-full" style="background-color: #ff5f57" />
          <span class="w-3 h-3 rounded-full" style="background-color: #febc2e" />
          <span class="w-3 h-3 rounded-full" style="background-color: #28c840" />
        </div>
        <div class="text-xs text-white/50">Aether — Agent Console</div>
        <div class="w-12" />
      </div>

      <!-- Body -->
      <div class="grid grid-cols-12 h-[520px] text-sm">
        <!-- Sidebar -->
        <aside class="col-span-3 border-r border-white/10 bg-black/30 p-4 flex flex-col gap-1">
          <button
            type="button"
            class="w-full inline-flex items-center gap-2 rounded-lg bg-white text-black text-xs font-semibold px-3 py-2 hover:bg-white/90 transition-colors"
          >
            <Sparkles class="w-3.5 h-3.5" />
            <span>部署智能体</span>
          </button>

          <nav class="mt-4 flex flex-col gap-0.5">
            <a
              v-for="item in SIDEBAR_ITEMS"
              :key="item.label"
              href="#"
              :class="[
                'flex items-center gap-2 px-2 py-1.5 rounded-md text-xs transition-colors',
                item.active ? 'bg-white/10 text-white' : 'text-white/60 hover:bg-white/5',
              ]"
            >
              <component :is="item.icon" class="w-3.5 h-3.5" />
              <span class="flex-1">{{ item.label }}</span>
              <span v-if="item.count !== undefined" class="text-[10px] text-white/40">{{ item.count }}</span>
            </a>
          </nav>

          <div class="mt-6">
            <p class="text-[10px] uppercase tracking-widest text-white/40 mb-2 px-2">环境</p>
            <ul class="flex flex-col gap-0.5">
              <li
                v-for="label in LABELS"
                :key="label.name"
                class="flex items-center gap-2 px-2 py-1.5 rounded-md text-xs text-white/60 hover:bg-white/5"
              >
                <span class="w-2.5 h-2.5 rounded-full" :style="{ backgroundColor: label.color }" />
                <span>{{ label.name }}</span>
              </li>
            </ul>
          </div>
        </aside>

        <!-- Task list -->
        <div class="col-span-4 border-r border-white/10 overflow-y-auto">
          <div class="flex items-center gap-2 px-3 h-10 border-b border-white/10 text-white/50">
            <Search class="w-3.5 h-3.5" />
            <span class="text-xs">搜索任务</span>
          </div>
          <ul>
            <li
              v-for="t in TASKS"
              :key="t.id"
              :class="[
                'px-3 py-3 border-b border-white/5 cursor-pointer',
                t.active ? 'bg-white/[0.06]' : 'hover:bg-white/[0.03]',
              ]"
              @click="activeTask = t"
            >
              <div class="flex items-center justify-between mb-0.5">
                <span :class="['text-xs', t.active ? 'text-white font-semibold' : 'text-white/80 font-medium']">
                  {{ t.agent }}
                </span>
                <span class="text-[10px] text-white/40">{{ t.time }}</span>
              </div>
              <div class="text-[11px] text-white/70 truncate">{{ t.task }}</div>
              <div class="text-[11px] text-white/40">{{ t.status }}</div>
            </li>
          </ul>
        </div>

        <!-- Detail panel -->
        <div class="col-span-5 flex flex-col">
          <div class="flex items-center justify-between px-4 h-10 border-b border-white/10">
            <div class="flex items-center gap-1">
              <button
                v-for="btn in [
                  { icon: Play, label: '运行' },
                  { icon: Archive, label: '归档' },
                  { icon: Trash2, label: '删除' },
                ]"
                :key="btn.label"
                type="button"
                :aria-label="btn.label"
                class="w-7 h-7 rounded-md flex items-center justify-center text-white/60 hover:bg-white/5 hover:text-white transition-colors"
              >
                <component :is="btn.icon" class="w-3.5 h-3.5" />
              </button>
            </div>
            <button
              type="button"
              aria-label="More"
              class="w-7 h-7 rounded-md flex items-center justify-center text-white/60 hover:bg-white/5 hover:text-white transition-colors"
            >
              <MoreHorizontal class="w-3.5 h-3.5" />
            </button>
          </div>

          <div class="flex-1 overflow-y-auto px-5 py-4">
            <h3 class="text-base font-semibold text-white">{{ activeTask.task }}</h3>

            <div class="mt-3 flex items-center gap-2.5">
              <div class="w-7 h-7 rounded-full bg-gradient-to-br from-[#00d2ff] to-[#0B2551] flex items-center justify-center text-[10px] font-semibold text-white">
                {{ activeTask.agent.charAt(0) }}
              </div>
              <div class="flex-1 min-w-0">
                <div class="text-xs text-white">
                  <span class="font-medium">{{ activeTask.agent }}</span>
                  <span class="text-white/50"> · {{ activeTask.time }}</span>
                </div>
              </div>
              <span class="px-2 py-0.5 rounded-full border border-white/10 text-[10px] text-white/70">
                {{ activeTask.status }}
              </span>
            </div>

            <div class="mt-5 rounded-lg border border-white/10 bg-white/[0.03] p-3">
              <div class="flex items-center gap-2 text-[11px] font-medium text-[#A4F4FD]">
                <Sparkles class="w-3.5 h-3.5" />
                <span>摘要 · Aether Agent</span>
              </div>
              <p class="mt-2 text-xs leading-[1.6] text-white/70">
                Agent 已完成任务执行。共处理 3 个文件，调用 5 个工具，无错误发生。
                输出结果已保存至工作目录。
              </p>
            </div>

            <div class="mt-5 space-y-3 text-xs leading-[1.7] text-white">
              <p>任务执行详情：</p>
              <p>
                本次 Agent 运行使用了 ReAct 推理循环，经过 3 轮迭代完成目标。
                工具调用链：read_file → analyze → edit_file，全程流式输出。
              </p>
              <p>
                上下文窗口使用率 34%，Token 消耗 12,480，预估成本 $0.03。
              </p>
              <p class="text-white/50">— Aether Agent Runtime</p>
            </div>

            <div class="mt-5 inline-flex items-center gap-2 px-3 py-1.5 rounded-full border border-white/10 text-[11px] text-white/80">
              <Wrench class="w-3 h-3" />
              <span>tools-invoked.json</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
