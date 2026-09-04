export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) { super(message); this.status = status }
}
export function createApi(token: string, expired: () => void) {
  async function raw(path: string, init: RequestInit = {}) {
    const headers = new Headers(init.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (typeof init.body === 'string') headers.set('Content-Type', 'application/json')
    const deadline = AbortSignal.timeout(path.endsWith('/answers/stream') ? 150000 : 30000)
    const response = await fetch(`/api${path}`, { ...init, headers, signal: init.signal ? AbortSignal.any([init.signal, deadline]) : deadline })
    if (!response.ok) {
      if (response.status === 401 && token) expired()
      const body = await response.json().catch(() => ({}))
      const fallback: Record<number, string> = {401:'登录已失效，请重新登录',403:'你没有执行此操作的权限',404:'内容不存在或访问权限已变更',409:'操作冲突，请刷新后重试',429:'请求过于频繁，请稍后重试',503:'服务暂时不可用，请稍后重试'}
      throw new ApiError(response.status, fallback[response.status] ?? body.detail ?? '请求失败，请稍后重试')
    }
    return response
  }
  async function json<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await raw(path, init)
    return response.status === 204 ? undefined as T : response.json()
  }
  return { raw, json }
}
export type Api = ReturnType<typeof createApi>
export const errorText = (cause: unknown) => cause instanceof Error ? cause.name === 'TimeoutError' ? '请求超时，请稍后重试' : cause instanceof TypeError ? '连接失败，请检查网络后重试' : cause.message : '连接失败，请检查网络后重试'
