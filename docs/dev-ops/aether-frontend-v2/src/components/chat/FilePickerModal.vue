<script setup lang="ts">
/**
 * FilePickerModal — 选择本地文件夹（Agent 直接操作本机原件）
 *
 * 用户输入本机文件夹的绝对路径 → 前端转换为 fs 服务的 /workspace/local/<盘符>/... 路径
 * → 验证并预览文件树 → 确认后把该路径附加给 Agent。
 *
 * 路径映射（fs 服务将本机磁盘绑定到工作区）：
 *   C:\Users\me\docs  ↔  /workspace/local/c/Users/me/docs
 *   D:\code\Agent-test ↔ /workspace/local/d/code/Agent-test
 *
 * Agent 的 read_file / write_file / list_directory 等工具直接读写这些 /workspace/local/... 路径，
 * 即直接操作本机原始文件（非副本）。单个文件路径也可直接附加。
 */
import { ref, computed } from 'vue'
import {
  X, FileText, Loader2, HardDrive,
  FolderOpen, ChevronRight, Link, AlertCircle,
} from 'lucide-vue-next'
import { fsApi, type FileEntry } from '@/api/services/python-services'

const emit = defineEmits<{
  close: []
  uploaded: [files: { name: string; path: string; size: number }[]]
}>()

// ── 状态 ──────────────────────────────────────────────────

const rawPath = ref('')              // 用户输入的本机路径
const workspacePath = ref<string | null>(null) // 转换后的 /workspace/local/... 路径
const validating = ref(false)
const fileInfo = ref<{ is_dir: boolean; size_bytes: number | null } | null>(null)
const entries = ref<FileEntry[]>([])          // 目录树预览
const browseLoading = ref(false)
const browseStack = ref<string[]>([])         // 已进入的子目录名
const confirming = ref(false)
const error = ref('')

const shortcutPaths = computed(() => [
  { label: '桌面', path: 'C:\\Users\\Public\\Desktop' },
  { label: '文档', path: 'C:\\Users\\Public\\Documents' },
  { label: '下载', path: 'C:\\Users\\Public\\Downloads' },
  { label: '代码', path: 'D:\\code' },
  { label: 'D盘', path: 'D:\\' },
])

// ── 路径转换 ──────────────────────────────────────────────

/** 将本机绝对路径转换为 fs 服务的 /workspace/local/... 路径；无法识别时返回 null */
function toWorkspacePath(hostPath: string): string | null {
  const p = hostPath.trim()
  if (!p) return null
  // Windows 盘符: X:\... 或 X:/...
  const m = p.match(/^([a-zA-Z]):[\\/](.*)$/)
  if (m) {
    const drive = m[1].toLowerCase()
    const rest = m[2].replace(/\\/g, '/').replace(/\/+$/, '')
    return `/workspace/local/${drive}${rest ? '/' + rest : ''}`
  }
  // Unix 绝对路径: /foo/bar → /workspace/local/foo/bar（挂载盘符下对应目录）
  if (p.startsWith('/')) {
    return `/workspace/local${p.replace(/\/+$/, '')}`
  }
  return null
}

function basenamePath(p: string): string {
  const norm = p.replace(/\/+$/, '')
  const idx = norm.lastIndexOf('/')
  return idx >= 0 ? norm.slice(idx + 1) : norm
}

/** 当前预览的工作区路径（含已进入的子目录） */
const currentWsPath = computed(() => {
  const base = workspacePath.value || ''
  const sub = browseStack.value.join('/')
  return sub ? `${base}/${sub}` : base
})

/** 面包屑：盘符段显示为 D:\ 形式 */
const breadcrumbParts = computed(() => {
  const base = currentWsPath.value.replace(/^\/workspace\/local\//, '')
  const segs = base.split('/').filter(Boolean)
  if (segs.length === 0) return []
  const first = segs[0]
  const head = first.length === 1 ? first.toUpperCase() + ':\\' : first
  return [head, ...segs.slice(1)]
})

// ── 验证 ─────────────────────────────────────────────────

async function refreshEntries() {
  if (!fileInfo.value?.is_dir) return
  try {
    entries.value = (await fsApi.listDir(currentWsPath.value)).entries
  } catch {
    entries.value = []
  } finally {
    browseLoading.value = false
  }
}

async function doValidate() {
  const path = rawPath.value.trim()
  if (!path) return

  const wp = toWorkspacePath(path)
  if (!wp) {
    workspacePath.value = null
    fileInfo.value = null
    entries.value = []
    browseStack.value = []
    error.value = '无法识别该路径，请输入本机绝对路径（如 D:\\code\\my-data）'
    return
  }

  validating.value = true
  error.value = ''
  workspacePath.value = wp
  fileInfo.value = null
  entries.value = []
  browseStack.value = []
  try {
    const stat = await fsApi.exists(wp)
    if (!stat.exists) {
      error.value = '路径不存在或无法访问，请确认本机路径正确'
      return
    }
    fileInfo.value = { is_dir: stat.is_dir, size_bytes: stat.size_bytes }
    if (stat.is_dir) {
      browseLoading.value = true
      await refreshEntries()
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '无法访问该路径'
  } finally {
    validating.value = false
  }
}

async function enterSubdir(name: string) {
  browseStack.value.push(name)
  browseLoading.value = true
  await refreshEntries()
}

async function goUpBrowse() {
  if (browseStack.value.length === 0) return
  browseStack.value.pop()
  browseLoading.value = true
  await refreshEntries()
}

function useShortcut(path: string) {
  rawPath.value = path
  doValidate()
}

// ── 确认并附加 ───────────────────────────────────────────

const canConfirm = computed(() =>
  workspacePath.value != null && fileInfo.value != null && !confirming.value)

async function confirm() {
  if (!canConfirm.value) return
  confirming.value = true
  error.value = ''
  try {
    const isFile = fileInfo.value != null && fileInfo.value.is_dir === false
    const attach = isFile
      ? { name: basenamePath(workspacePath.value!), path: workspacePath.value!, size: fileInfo.value?.size_bytes || 0 }
      : { name: basenamePath(currentWsPath.value), path: currentWsPath.value, size: 0 }
    emit('uploaded', [attach])
    emit('close')
  } finally {
    confirming.value = false
  }
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}
</script>

<template>
  <Transition name="fp-modal">
    <div class="fixed inset-0 z-50 flex items-center justify-center"
         style="background: rgba(0,0,0,0.55); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px)"
         @click.self="emit('close')">
      <div class="rounded-2xl border overflow-hidden flex flex-col"
           style="width: 640px; max-height: 80vh; background: rgba(30,30,32,0.98); border-color: rgba(255,255,255,0.08)">

        <!-- Header -->
        <div class="flex items-center justify-between px-5 py-3.5 border-b" style="border-color: rgba(255,255,255,0.06)">
          <div class="flex items-center gap-2.5">
            <HardDrive class="w-4 h-4" style="color: #5AC8FA" />
            <h3 class="text-[15px] font-semibold" style="color: #F5F5F7">选择本地文件夹</h3>
          </div>
          <button @click="emit('close')" class="w-7 h-7 flex items-center justify-center rounded-lg" style="color: #8E8E93">
            <X class="w-4 h-4" />
          </button>
        </div>

        <!-- Body -->
        <div class="flex-1 overflow-y-auto p-5 flex flex-col gap-4">

          <!-- 说明 -->
          <p class="text-[12px]" style="color: #8E8E93">
            输入本机文件夹的绝对路径，Agent 将<strong style="color: #5AC8FA">直接读写</strong>其中的原始文件（非副本）。
            <span class="block mt-1" style="color: #636366">如 <code>D:\code\Agent-test</code> 或 <code>C:\Users\Public\Desktop</code></span>
          </p>

          <!-- 路径输入 -->
          <div>
            <div class="flex gap-2">
              <input v-model="rawPath" type="text"
                     placeholder="如 D:\code\Agent-test"
                     class="flex-1 rounded-lg px-3.5 py-2.5 text-[13px] border outline-none"
                     style="background: rgba(255,255,255,0.04); color: #F5F5F7; border-color: rgba(255,255,255,0.08)"
                     :style="error ? 'border-color: #FF453A' : ''"
                     @keydown.enter="doValidate" />
              <button @click="doValidate" :disabled="validating || !rawPath.trim()"
                      class="px-5 py-2.5 rounded-xl text-[13px] font-medium active:scale-95 disabled:opacity-30 flex items-center gap-2"
                      style="background: #5AC8FA; color: #000">
                <Loader2 v-if="validating" class="w-4 h-4 animate-spin" />
                <span v-else>验证</span>
              </button>
            </div>
            <div class="flex flex-wrap gap-1.5 mt-2">
              <button v-for="sc in shortcutPaths" :key="sc.path" @click="useShortcut(sc.path)"
                      class="px-2.5 py-1 rounded-lg text-[11px]" style="background: rgba(255,255,255,0.04); color: #8E8E93">
                {{ sc.label }}
              </button>
            </div>
          </div>

          <!-- 错误 -->
          <div v-if="error"
               class="flex items-center gap-2 px-4 py-3 rounded-xl text-[13px]"
               style="background: rgba(255,69,58,0.08); color: #FF453A">
            <AlertCircle class="w-4 h-4 flex-shrink-0" /> {{ error }}
          </div>

          <!-- 单个文件信息 -->
          <div v-if="fileInfo && fileInfo.is_dir === false"
               class="flex items-center gap-2.5 px-4 py-3 rounded-xl"
               style="background: rgba(90,200,250,0.08); border: 1px solid rgba(90,200,250,0.2)">
            <FileText class="w-4 h-4 flex-shrink-0" style="color: #5AC8FA" />
            <div class="min-w-0">
              <div class="text-[13px] truncate" style="color: #F5F5F7">{{ basenamePath(workspacePath!) }}</div>
              <div class="text-[11px] truncate" style="color: #8E8E93">{{ workspacePath }}</div>
            </div>
            <span class="text-[11px] ml-auto flex-shrink-0" style="color: #636366">{{ formatSize(fileInfo.size_bytes || 0) }}</span>
          </div>

          <!-- 文件夹文件树预览 -->
          <div v-if="fileInfo && fileInfo.is_dir === true"
               class="rounded-xl border overflow-hidden" style="border-color: rgba(255,255,255,0.06)">
            <div class="flex items-center gap-1 px-3 py-2 text-[11px] border-b overflow-x-auto"
                 style="border-color: rgba(255,255,255,0.04); color: #8E8E93">
              <button @click="goUpBrowse" class="hover:opacity-80 flex-shrink-0"
                      :style="{ color: browseStack.length > 0 ? '#5AC8FA' : '#636366' }"
                      :disabled="browseStack.length === 0">
                ↩ 上级
              </button>
              <template v-for="(part, i) in breadcrumbParts" :key="i">
                <ChevronRight class="w-3 h-3 flex-shrink-0" />
                <span :style="{ color: i === breadcrumbParts.length - 1 ? '#F5F5F7' : '#8E8E93' }"
                      class="truncate max-w-[120px]">{{ part }}</span>
              </template>
            </div>
            <div class="max-h-[200px] overflow-y-auto">
              <div v-if="browseLoading" class="flex justify-center py-8">
                <Loader2 class="w-5 h-5 animate-spin" style="color: #5AC8FA" />
              </div>
              <div v-else-if="entries.length === 0" class="px-4 py-6 text-center text-[12px]" style="color: #636366">
                空文件夹
              </div>
              <div v-for="entry in entries" :key="entry.name"
                   @click="entry.type === 'dir' ? enterSubdir(entry.name) : undefined"
                   class="flex items-center gap-2.5 px-3 py-1.5 text-[13px] transition-colors"
                   :class="entry.type === 'dir' ? 'cursor-pointer hover:bg-white/5' : ''"
                   :style="{ color: entry.type === 'dir' ? '#F5F5F7' : '#98989D' }">
                <FolderOpen v-if="entry.type === 'dir'" class="w-4 h-4 flex-shrink-0" style="color: #5AC8FA" />
                <FileText v-else class="w-4 h-4 flex-shrink-0" style="color: #8E8E93" />
                <span class="truncate flex-1">{{ entry.name }}</span>
                <span v-if="entry.type === 'file'" class="text-[10px]" style="color: #636366">{{ formatSize(entry.size_bytes) }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="px-5 py-3 border-t flex-shrink-0" style="border-color: rgba(255,255,255,0.08)">
          <button @click="confirm"
                  :disabled="!canConfirm"
                  class="w-full py-2.5 rounded-xl text-[14px] font-semibold active:scale-95 flex items-center justify-center gap-2 disabled:opacity-30"
                  style="background: linear-gradient(135deg, #5AC8FA, #64D2FF); color: #000">
            <Loader2 v-if="confirming" class="w-4 h-4 animate-spin" />
            <Link v-else class="w-4 h-4" />
            {{ confirming ? '正在附加...' : '确认并让 Agent 直接操作此文件夹' }}
          </button>
          <p v-if="!canConfirm" class="text-[11px] mt-2 text-center" style="color: #636366">
            先输入本机文件夹路径并验证
          </p>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.fp-modal-enter-active { transition: opacity 200ms ease-out; }
.fp-modal-leave-active { transition: opacity 150ms ease-in; }
.fp-modal-enter-from, .fp-modal-leave-to { opacity: 0; }
@media (prefers-reduced-motion: reduce) {
  .fp-modal-enter-active, .fp-modal-leave-active { transition: none; }
}
</style>
