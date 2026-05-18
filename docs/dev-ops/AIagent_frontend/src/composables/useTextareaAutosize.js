/**
 * Auto-resize a textarea element on input.
 * Limits max height to 150px, matching original behavior.
 */
export function useTextareaAutosize() {
  function onInput(e) {
    const el = e.target
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 150) + 'px'
  }

  function resetHeight(el) {
    if (el) el.style.height = 'auto'
  }

  return { onInput, resetHeight }
}
