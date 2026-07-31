<script setup lang="ts">
import { ref } from 'vue'
import { X, Copy, Download, Check } from 'lucide-vue-next'

const props = defineProps<{
  visible: boolean
  yaml: string
}>()

const emit = defineEmits<{
  close: []
}>()

const copied = ref(false)

async function handleCopy() {
  try {
    await navigator.clipboard.writeText(props.yaml)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    // Fallback
    const textarea = document.createElement('textarea')
    textarea.value = props.yaml
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  }
}

function handleDownload() {
  const blob = new Blob([props.yaml], { type: 'text/yaml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'workflow.yaml'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div
        v-if="visible"
        class="fixed inset-0 z-50 flex items-center justify-center p-4"
      >
        <!-- Backdrop -->
        <div
          class="absolute inset-0 bg-black/60 backdrop-blur-sm"
          @click="emit('close')"
        />

        <!-- Dialog -->
        <div
          class="relative w-full max-w-2xl bg-surface-card border border-white/10 rounded-xl
                 shadow-2xl flex flex-col max-h-[80vh]"
        >
          <!-- Header -->
          <div class="flex items-center justify-between px-5 py-4 border-b border-white/5">
            <h3 class="text-sm font-medium text-primary">导出工作流 YAML</h3>
            <button
              @click="emit('close')"
              class="p-1 rounded-md text-secondary hover:text-primary hover:bg-white/5 transition-colors"
            >
              <X class="w-4 h-4" />
            </button>
          </div>

          <!-- YAML content -->
          <div class="flex-1 overflow-auto p-0">
            <pre
              class="p-5 m-0 text-xs font-mono text-primary/80 leading-relaxed
                     overflow-x-auto whitespace-pre bg-surface-base/50"
            ><code>{{ yaml || '暂无内容' }}</code></pre>
          </div>

          <!-- Footer -->
          <div class="flex items-center justify-end gap-2 px-5 py-3 border-t border-white/5">
            <button
              @click="handleCopy"
              class="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
                     text-secondary hover:text-primary hover:bg-white/5
                     border border-white/10 transition-colors"
            >
              <Check v-if="copied" class="w-3.5 h-3.5 text-accent-green" />
              <Copy v-else class="w-3.5 h-3.5" />
              {{ copied ? '已复制' : '复制' }}
            </button>
            <button
              @click="handleDownload"
              class="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium
                     bg-primary/10 text-primary hover:bg-primary/20
                     border border-primary/20 transition-colors"
            >
              <Download class="w-3.5 h-3.5" />
              下载
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.2s ease;
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;
}

.dialog-fade-enter-active > :not(.absolute),
.dialog-fade-leave-active > :not(.absolute) {
  transition: transform 0.2s ease;
}

.dialog-fade-enter-from > :not(.absolute) {
  transform: scale(0.95) translateY(8px);
}

.dialog-fade-leave-to > :not(.absolute) {
  transform: scale(0.95) translateY(8px);
}
</style>
