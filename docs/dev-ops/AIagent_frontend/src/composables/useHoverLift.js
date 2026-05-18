import { ref, computed, onUnmounted } from 'vue'

/**
 * Mouse-position-based 3D tilt effect for cards.
 */
export function useHoverLift(options = {}) {
  const intensity = options.intensity ?? 1
  const maxDeg = 3 * intensity

  const targetRef = ref(null)
  const rotateX = ref(0)
  const rotateY = ref(0)
  const isHovered = ref(false)

  function onMouseMove(e) {
    if (!targetRef.value) return
    const rect = targetRef.value.getBoundingClientRect()
    const x = (e.clientX - rect.left) / rect.width - 0.5
    const y = (e.clientY - rect.top) / rect.height - 0.5
    rotateX.value = -y * maxDeg
    rotateY.value = x * maxDeg
  }

  function onMouseEnter() {
    isHovered.value = true
  }

  function onMouseLeave() {
    isHovered.value = false
    rotateX.value = 0
    rotateY.value = 0
  }

  const style = computed(() => ({
    transform: isHovered.value
      ? `perspective(600px) rotateX(${rotateX.value}deg) rotateY(${rotateY.value}deg) translateZ(4px)`
      : 'perspective(600px) rotateX(0deg) rotateY(0deg) translateZ(0)',
    transition: isHovered.value ? 'none' : 'transform 0.5s var(--ease-out-expo)',
  }))

  return { targetRef, style, isHovered, onMouseMove, onMouseEnter, onMouseLeave }
}
