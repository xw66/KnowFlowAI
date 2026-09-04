import { onUnmounted, shallowRef } from 'vue'
import { errorText } from './api'

export function useScope() {
  const controller = new AbortController()
  const error = shallowRef('')
  const busy = shallowRef(false)
  onUnmounted(() => controller.abort())
  async function run(action: () => Promise<void>) {
    if (busy.value || controller.signal.aborted) return
    error.value = ''; busy.value = true
    try { await action() } catch (cause) {
      if (!controller.signal.aborted) error.value = errorText(cause)
    } finally { if (!controller.signal.aborted) busy.value = false }
  }
  return { signal: controller.signal, error, busy, run }
}
