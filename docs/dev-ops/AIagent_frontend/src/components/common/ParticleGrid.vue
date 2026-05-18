<script setup>
import { useParticleGrid } from '@/composables/useParticleGrid'

const props = defineProps({
  cols: { type: Number, default: 20 },
  rows: { type: Number, default: 15 },
  cellSize: { type: Number, default: 28 },
  maxOpacity: { type: Number, default: 0.6 },
  breatheInterval: { type: Number, default: 3000 },
})

const { cells, gridStyle, hoverCell, leaveCell } = useParticleGrid({
  cols: props.cols,
  rows: props.rows,
  cellSize: props.cellSize,
  maxOpacity: props.maxOpacity,
  breatheInterval: props.breatheInterval,
})
</script>

<template>
  <div class="particle-grid" :style="gridStyle">
    <div
      v-for="cell in cells"
      :key="cell.id"
      class="particle-grid__cell"
      :style="{ opacity: cell.opacity }"
      @mouseenter="hoverCell(cell.row, cell.col)"
      @mouseleave="leaveCell(cell.row, cell.col)"
    />
  </div>
</template>

<style scoped>
.particle-grid {
  position: absolute;
  inset: 0;
  gap: 0;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
  mask-image: radial-gradient(ellipse 60% 50% at 50% 40%, black 40%, transparent 100%);
}

.particle-grid__cell {
  aspect-ratio: 1;
  background: var(--grid-color);
  transition: opacity 0.8s var(--ease-out-expo);
  pointer-events: auto;
  border-radius: 1px;
}

.particle-grid__cell:hover {
  background: var(--grid-color-hover);
  transition: opacity 0.12s ease;
}
</style>
