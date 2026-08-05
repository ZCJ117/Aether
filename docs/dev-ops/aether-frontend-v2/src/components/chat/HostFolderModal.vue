<script setup lang="ts">
/**
 * HostFolderModal — 将本地文件夹桥接到 Agent 工作区
 *
 * 用户粘贴宿主机路径 → 后端验证 → 展示文件树 → 确认挂载
 * 挂载后 Agent 可直接操作宿主机上的原始文件。
 */
import { ref, computed } from 'vue'
import {
  X, FolderOpen, FileText, Loader2, CheckCircle2,
  HardDrive, ChevronRight, Link, AlertCircle,
} from 'lucide-vue-next'
import { bridgeApi, type FileEntry } from '@/api/services/python-services'

const emit = defineEmits<{
  close: []
  mounted: [payload: { workspacePath: string; hostPath: string; mountName: string }]
}>()

// ── State ──────────────────────────────────────────────────

const rawPath = ref('')
const validating = ref(false)
const validationResult = ref<{
  valid: boolean
  resolved: string | null
  display_name: string | null
  tree: FileEntry[]
  error: string | null
} | null>(null)

// Sub-browsing (navigate into subdirectories for preview)
const browseStack = ref<string[]>([])
const browseEntries = ref<FileEntry[]>([])
const browseLoading = ref(false)

const mounting = ref(false)
const mountResult = ref<{ workspacePath: string; hostPath: string; mountName: string } | null>(null)
const mountError = ref('')

// ── Common shortcut paths ─────────────────────────────────

const shortcutPaths = computed(() => {
  const paths: { label: string; path: string }[] = []
  paths.push(
    { label: '桌面', path: 'C:\\Users\\Public\\Desktop' },
    { label: '文档', path: 'C:\\Users\\Public\\Documents' },
    { label: '下载', path: 'C:\\Users\\Public\\Downloads' },
  )
  paths.push(
    { label: 'Home', path: '/home' },
    { label: '桌面 (macOS)', path: '/Users/Shared/Desktop' },
    { label: '文档 (macOS)', path: '/Users/Shared/Documents' },
  )
  return paths
})

// ── Validate ───────────────────────────────────────────────

async function doValidate() {
  const path = rawPath.value.trim()
  if (!path) return

  validating.value = true
  validationResult.value = null
  browseEntries.value = []
  browseStack.value = []

  try {
    const res = await bridgeApi.validate(path)
    validationResult.value = res
    if (res.valid && res.tree) {
      browseEntries.value = res.tree
    }
  } catch (e: unknown) {
    validationResult.value = {
      valid: false,
      resolved: null,
      display_name: null,
      tree: [],
      error: e instanceof Error ? e.message : '验证失败',
    }
  } finally {
    validating.value = false
  }
}

// ── Browse into subdirectory ───────────────────────────────

async function enterSubdir(name: string) {
  if (!validationResult.value?.resolved) return
  browseLoading.value = true

  const newSubpath = [...browseStack.value, name].join('/')
  try {
    const res = await bridgeApi.browse(validationResult.value.resolved, newSubpath)
    browseEntries.value = res.entries
    browseStack.value.push(name)
  } catch {
    // Keep current view on error
  } finally {
    browseLoading.value = false
  }
}

async function goUpBrowse() {
  if (browseStack.value.length === 0) {
    browseEntries.value = validationResult.value?.tree || []
    return
  }
  browseLoading.value = true
  browseStack.value.pop()
  const subpath = browseStack.value.join('/') || '.'

  try {
    const res = await bridgeApi.browse(validationResult.value!.resolved!, subpath)
    browseEntries.value = res.entries
  } catch {
    browseEntries.value = validationResult.value?.tree || []
  } finally {
    browseLoading.value = false
  }
}

// ── Mount ──────────────────────────────────────────────────

async function doMount() {
  if (!validationResult.value?.resolved || !validationResult.value?.display_name) return

  mounting.value = true
  mountError.value = ''

  const sessionId = `session-${Date.now()}`

  try {
    const res = await bridgeApi.mount(
      validationResult.value.resolved,
      validationResult.value.display_name,
      sessionId,
    )
    mountResult.value = {
      workspacePath: res.workspace_path,
      hostPath: res.host_path,
      mountName: res.name,
    }
  } catch (e: unknown) {
    mountError.value = e instanceof Error ? e.message : '挂载失败'
  } finally {
    mounting.value = false
  }
}

// ── Confirm ────────────────────────────────────────────────

function confirm() {
  if (mountResult.value) {
    emit('mounted', mountResult.value)
  }
  emit('close')
}

function useShortcut(path: string) {
  rawPath.value = path
  doValidate()
}

const formatSize = (bytes: number) => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

const breadcrumbParts = computed(() => {
  const base = validationResult.value?.display_name || ''
  return [base, ...browseStack.value]
})
</script>

<template>
  <Transition name="bridge-modal">
    <div
      class="fixed inset-0 z-50 flex items-center justify-center"
      style="background: rgba(0,0,0,0.55); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px)"
      @click.self="emit('close')"
    >
      <div
        class="rounded-2xl border overflow-hidden flex flex-col"
        style="width: 640px; max-height: 80vh; background: rgba(30,30,32,0.98); border-color: rgba(255,255,255,0.08)"
      >
        <!-- Header -->
        <div class="flex items-center justify-between px-5 py-3.5 border-b" style="border-color: rgba(255,255,255,0.06)">
          <div class="flex items-center gap-2.5">
            <HardDrive class="w-4 h-4" style="color: #30D158" />
            <h3 class="text-[15px] font-semibold" style="color: #F5F5F7">挂载本地文件夹</h3>
          </div>
          <button
            @click="emit('close')"
            class="w-7 h-7 flex items-center justify-center rounded-lg transition-colors"
            style="color: #8E8E93"
          >
            <X class="w-4 h-4" />
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 overflow-y-auto p-5 flex flex-col gap-4">

          <!-- Path input -->
          <div>
            <label class="text-[12px] mb-1.5 block" style="color: #8E8E93">输入宿主机上的文件夹路径</label>
            <div class="flex gap-2">
              <input
                v-model="rawPath"
                type="text"
                placeholder="如 D:\Projects\my-data 或 /home/user/docs"
                class="flex-1 rounded-lg px-3.5 py-2.5 text-[13px] border outline-none transition-colors"
                style="background: rgba(255,255,255,0.04); color: #F5F5F7; border-color: rgba(255,255,255,0.08)"
                :style="validationResult && !validationResult.valid ? 'border-color: #FF453A' : ''"
                @keydown.enter="doValidate"
              />
              <button
                @click="doValidate"
                :disabled="validating || !rawPath.trim()"
                class="px-5 py-2.5 rounded-xl text-[13px] font-medium transition-all active:scale-95 disabled:opacity-30"
                style="background: #30D158; color: #000"
              >
                <Loader2 v-if="validating" class="w-4 h-4 animate-spin" />
                <span v-else>验证</span>
              </button>
            </div>

            <!-- Shortcuts -->
            <div class="flex flex-wrap gap-1.5 mt-2">
              <button
                v-for="sc in shortcutPaths"
                :key="sc.path"
                @click="useShortcut(sc.path)"
                class="px-2.5 py-1 rounded-lg text-[11px] transition-colors"
                style="background: rgba(255,255,255,0.04); color: #8E8E93"
              >
                {{ sc.label }}
              </button>
            </div>
          </div>

          <!-- Validation error -->
          <div
            v-if="validationResult && !validationResult.valid"
            class="flex items-center gap-2 px-4 py-3 rounded-xl text-[13px]"
            style="background: rgba(255,69,58,0.08); color: #FF453A"
          >
            <AlertCircle class="w-4 h-4 flex-shrink-0" />
            {{ validationResult.error }}
          </div>

          <!-- File tree preview -->
          <div
            v-if="validationResult?.valid && browseEntries.length > 0"
            class="rounded-xl border overflow-hidden"
            style="border-color: rgba(255,255,255,0.06)"
          >
            <!-- Breadcrumb -->
            <div class="flex items-center gap-1 px-3 py-2 text-[11px] border-b overflow-x-auto"
                 style="border-color: rgba(255,255,255,0.04); color: #8E8E93">
              <button
                @click="goUpBrowse"
                class="hover:opacity-80 flex-shrink-0"
                :style="{ color: breadcrumbParts.length > 0 ? '#30D158' : '#8E8E93' }"
              >
                {{ breadcrumbParts.length > 0 ? '↩ 上级' : '📂' }}
              </button>
              <template v-for="(part, i) in breadcrumbParts" :key="i">
                <ChevronRight class="w-3 h-3 flex-shrink-0" />
                <span
                  :style="{ color: i === breadcrumbParts.length - 1 ? '#F5F5F7' : '#8E8E93' }"
                  class="truncate max-w-[120px]"
                >{{ part }}</span>
              </template>
            </div>

            <!-- Entries -->
            <div class="max-h-[260px] overflow-y-auto">
              <div v-if="browseLoading" class="flex justify-center py-8">
                <Loader2 class="w-5 h-5 animate-spin" style="color: #30D158" />
              </div>
              <div
                v-for="entry in browseEntries"
                :key="entry.name"
                @click="entry.type === 'dir' ? enterSubdir(entry.name) : undefined"
                class="flex items-center gap-2.5 px-3 py-1.5 text-[13px] transition-colors"
                :class="entry.type === 'dir' ? 'cursor-pointer hover:bg-white/5' : ''"
                :style="{ color: entry.type === 'dir' ? '#F5F5F7' : '#98989D' }"
              >
                <component
                  :is="entry.type === 'dir' ? FolderOpen : FileText"
                  class="w-4 h-4 flex-shrink-0"
                  :style="{ color: entry.type === 'dir' ? '#30D158' : '#8E8E93' }"
                />
                <span class="truncate flex-1">{{ entry.name }}</span>
                <span v-if="entry.type === 'file'" class="text-[10px] flex-shrink-0" style="color: #636366">
                  {{ formatSize(entry.size_bytes) }}
                </span>
              </div>
            </div>
          </div>

          <!-- Mount button -->
          <div v-if="validationResult?.valid && !mountResult">
            <button
              @click="doMount"
              :disabled="mounting"
              class="w-full py-2.5 rounded-xl text-[14px] font-semibold transition-all active:scale-95 disabled:opacity-40 flex items-center justify-center gap-2"
              style="background: linear-gradient(135deg, #30D158, #34C759); color: #000"
            >
              <Loader2 v-if="mounting" class="w-4 h-4 animate-spin" />
              <Link v-else class="w-4 h-4" />
              {{ mounting ? '挂载中...' : '挂载此文件夹' }}
            </button>
            <p class="text-[11px] mt-2 text-center" style="color: #8E8E93">
              挂载后 Agent 将能够直接读写此文件夹中的文件
            </p>
          </div>

          <!-- Mount error -->
          <div
            v-if="mountError"
            class="flex items-center gap-2 px-4 py-3 rounded-xl text-[13px]"
            style="background: rgba(255,69,58,0.08); color: #FF453A"
          >
            <AlertCircle class="w-4 h-4 flex-shrink-0" />
            {{ mountError }}
          </div>

          <!-- Mount success -->
          <div
            v-if="mountResult"
            class="flex flex-col gap-2 px-4 py-4 rounded-xl"
            style="background: rgba(48,209,88,0.08); border: 1px solid rgba(48,209,88,0.2)"
          >
            <div class="flex items-center gap-2">
              <CheckCircle2 class="w-5 h-5" style="color: #30D158" />
              <span class="text-[14px] font-medium" style="color: #30D158">挂载成功</span>
            </div>
            <div class="text-[12px] space-y-1" style="color: #8E8E93">
              <div>工作区路径: <code style="color: #F5F5F7">{{ mountResult.workspacePath }}</code></div>
              <div>宿主机路径: <code style="color: #F5F5F7">{{ mountResult.hostPath }}</code></div>
            </div>
            <p class="text-[11px] mt-1" style="color: #8E8E93">
              Agent 现在可以直接读写此文件夹中的所有文件
            </p>
          </div>
        </div>

        <!-- Footer -->
        <div class="px-5 py-3 border-t flex-shrink-0" style="border-color: rgba(255,255,255,0.08)">
          <button
            v-if="mountResult"
            @click="confirm"
            class="w-full py-2.5 rounded-xl text-[14px] font-semibold transition-all active:scale-95"
            style="background: linear-gradient(135deg, #30D158, #34C759); color: #000"
          >
            确认并附加到对话
          </button>
          <span v-else class="text-[12px] text-center block" style="color: #636366">
            输入路径并验证后挂载到工作区
          </span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.bridge-modal-enter-active { transition: opacity 200ms ease-out; }
.bridge-modal-leave-active { transition: opacity 150ms ease-in; }
.bridge-modal-enter-from, .bridge-modal-leave-to { opacity: 0; }

@media (prefers-reduced-motion: reduce) {
  .bridge-modal-enter-active, .bridge-modal-leave-active { transition: none; }
}
</style>
