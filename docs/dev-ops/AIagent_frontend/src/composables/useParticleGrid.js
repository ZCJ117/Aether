import { ref, onMounted, onUnmounted } from 'vue'

export function useParticleGrid(options = {}) {
  const cols = options.cols ?? 20
  const rows = options.rows ?? 15
  const cellSize = options.cellSize ?? 28
  const breatheInterval = options.breatheInterval ?? 3000
  const maxOpacity = options.maxOpacity ?? 0.6

  const cells = ref([])
  const containerRef = ref(null)
  let breatheTimer = null

  function initCells() {
    const result = []
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        result.push({
          id: `${r}-${c}`,
          row: r,
          col: c,
          opacity: Math.random() * maxOpacity * 0.5,
        })
      }
    }
    cells.value = result
  }

  function breathe() {
    const count = Math.floor(cells.value.length * 0.08)
    for (let i = 0; i < count; i++) {
      const idx = Math.floor(Math.random() * cells.value.length)
      const target = Math.random() * maxOpacity
      cells.value[idx] = { ...cells.value[idx], opacity: target }
    }
  }

  function hoverCell(row, col) {
    const idx = row * cols + col
    if (idx < cells.value.length) {
      cells.value[idx] = { ...cells.value[idx], opacity: Math.min(1, maxOpacity + 0.3) }
    }
  }

  function leaveCell(row, col) {
    const idx = row * cols + col
    if (idx < cells.value.length) {
      cells.value[idx] = { ...cells.value[idx], opacity: Math.random() * maxOpacity * 0.5 }
    }
  }

  const gridStyle = {
    display: 'grid',
    gridTemplateColumns: `repeat(${cols}, ${cellSize}px)`,
    gridTemplateRows: `repeat(${rows}, ${cellSize}px)`,
  }

  onMounted(() => {
    initCells()
    breatheTimer = setInterval(breathe, breatheInterval)
  })

  onUnmounted(() => {
    if (breatheTimer) clearInterval(breatheTimer)
  })

  return { cells, containerRef, gridStyle, hoverCell, leaveCell }
}
