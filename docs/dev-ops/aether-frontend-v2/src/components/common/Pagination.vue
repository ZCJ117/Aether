<script setup lang="ts">
import { computed } from 'vue'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'

const props = defineProps<{
  page: number
  pageSize: number
  total: number
}>()

const emit = defineEmits<{
  'update:page': [page: number]
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

const startItem = computed(() => props.total > 0 ? (props.page - 1) * props.pageSize + 1 : 0)
const endItem = computed(() => Math.min(props.page * props.pageSize, props.total))

const visiblePages = computed(() => {
  const pages: (number | 'ellipsis')[] = []
  const tp = totalPages.value
  const p = props.page

  if (tp <= 7) {
    for (let i = 1; i <= tp; i++) pages.push(i)
    return pages
  }

  pages.push(1)
  if (p > 3) pages.push('ellipsis')

  const start = Math.max(2, p - 1)
  const end = Math.min(tp - 1, p + 1)
  for (let i = start; i <= end; i++) pages.push(i)

  if (p < tp - 2) pages.push('ellipsis')
  pages.push(tp)

  return pages
})

function goTo(pageNum: number) {
  if (pageNum >= 1 && pageNum <= totalPages.value && pageNum !== props.page) {
    emit('update:page', pageNum)
  }
}

function prev() {
  if (props.page > 1) emit('update:page', props.page - 1)
}

function next() {
  if (props.page < totalPages.value) emit('update:page', props.page + 1)
}
</script>

<template>
  <div v-if="total > 0" class="flex items-center justify-between pt-4">
    <span class="text-xs text-secondary">
      第 {{ startItem }}-{{ endItem }} 条，共 {{ total }} 条
    </span>

    <div class="flex items-center gap-1">
      <button
        @click="prev"
        :disabled="page <= 1"
        class="p-1.5 rounded text-secondary hover:text-primary hover:bg-white/[0.05]
               disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
      >
        <ChevronLeft class="w-4 h-4" />
      </button>

      <template v-for="item in visiblePages" :key="item">
        <span v-if="item === 'ellipsis'" class="text-xs text-secondary px-1">...</span>
        <button
          v-else
          @click="goTo(item)"
          :class="item === page
            ? 'bg-accent-blue/10 text-accent-blue'
            : 'text-secondary hover:text-primary hover:bg-white/[0.05]'"
          class="w-8 h-8 rounded text-xs font-medium transition-colors"
        >
          {{ item }}
        </button>
      </template>

      <button
        @click="next"
        :disabled="page >= totalPages"
        class="p-1.5 rounded text-secondary hover:text-primary hover:bg-white/[0.05]
               disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
      >
        <ChevronRight class="w-4 h-4" />
      </button>
    </div>
  </div>
</template>
