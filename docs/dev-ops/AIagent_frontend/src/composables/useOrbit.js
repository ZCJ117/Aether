import { ref, computed, onMounted, onUnmounted } from 'vue'

/**
 * Computes positions for N elements arranged in a circular orbit.
 */
export function useOrbit(options = {}) {
  const count = options.count ?? 6
  const radius = options.radius ?? 120
  const speed = options.speed ?? 25
  const clockwise = options.clockwise ?? true
  const startAngleOffset = options.startAngleOffset ?? 0

  const isPaused = ref(false)
  const angles = ref([])
  let rafId = null
  let lastTime = 0

  function initAngles() {
    const arr = []
    for (let i = 0; i < count; i++) {
      arr.push(startAngleOffset + (i * 360) / count)
    }
    angles.value = arr
  }

  function animate(timestamp) {
    if (lastTime === 0) lastTime = timestamp
    const delta = (timestamp - lastTime) / 1000
    lastTime = timestamp

    if (!isPaused.value) {
      const dir = clockwise ? 1 : -1
      const degPerSec = 360 / speed
      angles.value = angles.value.map((a) => ((a + dir * degPerSec * delta) % 360 + 360) % 360)
    }

    rafId = requestAnimationFrame(animate)
  }

  const items = computed(() =>
    angles.value.map((angle, i) => {
      const rad = (angle * Math.PI) / 180
      return {
        id: i,
        angle,
        x: Math.cos(rad) * radius,
        y: Math.sin(rad) * radius,
        style: {
          position: 'absolute',
          left: '50%',
          top: '50%',
          transform: `translate(calc(-50% + ${Math.cos(rad) * radius}px), calc(-50% + ${Math.sin(rad) * radius}px))`,
          margin: 0,
        },
      }
    })
  )

  function pause() {
    isPaused.value = true
  }
  function resume() {
    isPaused.value = false
  }

  onMounted(() => {
    initAngles()
    rafId = requestAnimationFrame(animate)
  })

  onUnmounted(() => {
    if (rafId) cancelAnimationFrame(rafId)
  })

  return { items, isPaused, pause, resume }
}
