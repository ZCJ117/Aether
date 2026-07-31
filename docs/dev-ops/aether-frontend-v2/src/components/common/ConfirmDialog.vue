<script setup lang="ts">
import { watch } from 'vue'

const props = withDefaults(defineProps<{
  visible: boolean
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
}>(), {
  confirmText: '确认',
  cancelText: '取消',
  danger: false,
})

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

function onConfirm() {
  emit('confirm')
}

function onCancel() {
  emit('cancel')
}

// Prevent body scroll when modal is open
watch(() => props.visible, (val) => {
  if (val) {
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = ''
  }
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div
        v-if="visible"
        class="fixed inset-0 z-50 flex items-center justify-center"
        @click.self="onCancel"
      >
        <!-- Overlay -->
        <div class="absolute inset-0 bg-black/60 backdrop-blur-sm" />

        <!-- Card -->
        <div class="relative bg-surface-card border border-white/5 rounded-xl p-6 max-w-md w-full mx-4 shadow-2xl">
          <h3 class="text-lg font-semibold text-primary mb-2">{{ title }}</h3>
          <p class="text-sm text-secondary mb-6">{{ message }}</p>

          <div class="flex justify-end gap-3">
            <button
              @click="onCancel"
              class="px-4 py-2 rounded-lg text-sm font-medium text-secondary
                     hover:text-primary hover:bg-white/[0.05] transition-colors"
            >
              {{ cancelText }}
            </button>
            <button
              @click="onConfirm"
              :class="danger
                ? 'bg-accent-red/10 text-accent-red hover:bg-accent-red/20'
                : 'bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20'"
              class="px-4 py-2 rounded-lg text-sm font-medium transition-colors"
            >
              {{ confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s ease;
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-active .relative,
.modal-leave-active .relative {
  transition: transform 0.2s ease;
}
.modal-enter-from .relative {
  transform: scale(0.95);
}
.modal-leave-to .relative {
  transform: scale(0.95);
}
</style>
