<script setup>
import { useOrbit } from '@/composables/useOrbit'

const props = defineProps({
  radius: { type: Number, default: 130 },
  speed: { type: Number, default: 25 },
  count: { type: Number, default: 6 },
  clockwise: { type: Boolean, default: true },
  startAngleOffset: { type: Number, default: 0 },
})

const { items, pause, resume } = useOrbit({
  count: props.count,
  radius: props.radius,
  speed: props.speed,
  clockwise: props.clockwise,
  startAngleOffset: props.startAngleOffset,
})
</script>

<template>
  <div class="orbit-ring" @mouseenter="pause" @mouseleave="resume">
    <div class="orbit-ring__center">
      <slot name="center" />
    </div>
    <div
      v-for="item in items"
      :key="item.id"
      class="orbit-ring__item"
      :style="item.style"
    >
      <slot name="item" :item="item" :index="item.id" />
    </div>
    <svg class="orbit-ring__track" viewBox="0 0 200 200">
      <circle cx="100" cy="100" :r="radius * 0.77" fill="none" stroke="var(--border-light)" stroke-width="0.5" stroke-dasharray="4 6" />
      <circle cx="100" cy="100" :r="radius * 0.48" fill="none" stroke="var(--border-light)" stroke-width="0.5" stroke-dasharray="4 6" />
    </svg>
  </div>
</template>

<style scoped>
.orbit-ring {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  max-width: 340px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.orbit-ring__center {
  position: relative;
  z-index: 2;
}

.orbit-ring__item {
  position: absolute;
  z-index: 1;
  will-change: transform;
  transition: transform 0.1s linear;
}

.orbit-ring__track {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
