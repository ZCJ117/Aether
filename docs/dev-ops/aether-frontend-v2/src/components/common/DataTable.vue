<script setup lang="ts">
import { computed } from 'vue'
import { ArrowUpDown, ArrowUp, ArrowDown } from 'lucide-vue-next'
import EmptyState from './EmptyState.vue'

export interface DataTableColumn {
  key: string
  title: string
  width?: string
  sortable?: boolean
}

const props = withDefaults(defineProps<{
  columns: DataTableColumn[]
  data: any[]
  loading?: boolean
}>(), {
  loading: false,
})

defineEmits<{
  'row-click': [row: any]
}>()

const gridTemplate = computed(() => {
  return props.columns
    .map((col) => col.width ?? '1fr')
    .join(' ')
})

const skeletonRows = computed(() => Array.from({ length: 5 }))
</script>

<template>
  <div class="rounded-xl border border-white/5 overflow-hidden">
    <!-- Table -->
    <div v-if="!loading && data.length > 0">
      <!-- Header -->
      <div
        class="grid items-center px-4 py-2.5 bg-white/[0.02] border-b border-white/5"
        :style="{ gridTemplateColumns: gridTemplate }"
      >
        <div
          v-for="col in columns"
          :key="col.key"
          class="flex items-center gap-1 text-xs font-medium text-secondary"
        >
          {{ col.title }}
          <ArrowUpDown v-if="col.sortable" class="w-3 h-3 opacity-40" />
        </div>
      </div>

      <!-- Rows -->
      <div
        v-for="(row, idx) in data"
        :key="idx"
        @click="$emit('row-click', row)"
        class="grid items-center px-4 py-2.5 cursor-pointer transition-colors hover:bg-white/[0.03]"
        :class="idx % 2 === 0 ? 'bg-surface-card' : ''"
        :style="{ gridTemplateColumns: gridTemplate }"
      >
        <div
          v-for="col in columns"
          :key="col.key"
          class="text-sm text-primary truncate pr-2"
        >
          <slot :name="`cell-${col.key}`" :row="row" :value="row[col.key]">
            {{ row[col.key] }}
          </slot>
        </div>
      </div>
    </div>

    <!-- Loading state -->
    <div v-else-if="loading">
      <div
        v-for="(_, idx) in skeletonRows"
        :key="idx"
        class="grid items-center px-4 py-3 animate-pulse"
        :style="{ gridTemplateColumns: gridTemplate }"
      >
        <div
          v-for="col in columns"
          :key="col.key"
          class="h-4 bg-white/[0.03] rounded"
        />
      </div>
    </div>

    <!-- Empty state -->
    <EmptyState
      v-else
      title="暂无数据"
      description="当前没有可显示的数据"
    />
  </div>
</template>
