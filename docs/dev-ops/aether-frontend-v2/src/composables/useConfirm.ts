import { ref, type Ref } from 'vue'

interface ConfirmOptions {
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  onConfirm?: () => void | Promise<void>
}

export function useConfirm(): {
  visible: Ref<boolean>
  title: Ref<string>
  message: Ref<string>
  confirmText: Ref<string>
  cancelText: Ref<string>
  onConfirm: Ref<(() => void | Promise<void>) | null>
  confirm: () => void
  cancel: () => void
  show: (opts: ConfirmOptions) => void
} {
  const visible = ref(false)
  const title = ref('')
  const message = ref('')
  const confirmText = ref('Confirm')
  const cancelText = ref('Cancel')
  const onConfirm = ref<(() => void | Promise<void>) | null>(null)

  function show(opts: ConfirmOptions): void {
    title.value = opts.title
    message.value = opts.message
    confirmText.value = opts.confirmText ?? 'Confirm'
    cancelText.value = opts.cancelText ?? 'Cancel'
    onConfirm.value = opts.onConfirm ?? null
    visible.value = true
  }

  async function confirm(): Promise<void> {
    if (onConfirm.value) {
      await onConfirm.value()
    }
    visible.value = false
  }

  function cancel(): void {
    visible.value = false
  }

  return {
    visible,
    title,
    message,
    confirmText,
    cancelText,
    onConfirm,
    confirm,
    cancel,
    show,
  }
}
