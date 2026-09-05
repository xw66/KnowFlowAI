import type { Api } from './api'
import type { Department } from './types'

export async function loadDepartments(api: Api, signal: AbortSignal) {
  const result: Department[] = []
  for (;;) {
    const page = await api.json<Department[]>(`/departments?limit=100&afterId=${result.at(-1)?.id ?? 0}`, { signal })
    signal.throwIfAborted()
    result.push(...page)
    if (page.length < 100) return result
  }
}
