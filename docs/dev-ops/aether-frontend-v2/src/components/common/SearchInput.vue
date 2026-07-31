<script setup lang="ts">
import { Search, X } from 'lucide-vue-next'
import { ref } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
}>(), {
  placeholder: '搜索...',
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'search': []
}>()

const inputRef = ref<HTMLInputElement | null>(null)

function onInput(e: Event) {
  const target = e.target as HTMLInputElement
  emit('update:modelValue', target.value)
}

function onClear() {
  emit('update:modelValue', '')
  inputRef.value?.focus()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    emit('search')
  }
}
</script>

<template>
  <div class="relative">
    <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-secondary pointer-events-none" />
    <input
      ref="inputRef"
      type="text"
      :value="modelValue"
      :placeholder="placeholder"
      @input="onInput"
      @keydown="onKeydown"
      class="w-full bg-white/[0.03] border border-white/5 rounded-lg pl-9 pr-8 py-1.5 text-sm
             text-primary placeholder:text-secondary/50
             focus:outline-none focus:border-accent-blue/50 focus:bg-white/[0.05]
             transition-colors"
    />
    <button
      v-if="modelValue"
      @click="onClear"
      class="absolute right-3 top-1/2 -translate-y-1/2 text-secondary hover:text-primary transition-colors"
    >
      <X class="w-3.5 h-3.5" />
    </button>
  </div>
</template>
