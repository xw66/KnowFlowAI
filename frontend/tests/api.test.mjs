import test from 'node:test'
import assert from 'node:assert/strict'
import { createApi } from '../src/api.ts'
import { dateLabel } from '../src/types.ts'

test('后端 UTC 时间与实时对话时间使用同一时区', () => {
  assert.equal(dateLabel('2026-09-04T10:08:00'), dateLabel('2026-09-04T10:08:00Z'))
})

test('统一附带 JWT，204 不解析 JSON，401 通知退出', async t => {
  let expired = 0
  const api = createApi('test-only-token', () => expired++)
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(init.headers.get('Authorization'), 'Bearer test-only-token')
    return url.endsWith('/members/1') ? new Response(null, { status: 204 }) : Response.json({}, { status: 401 })
  })
  assert.equal(await api.json('/members/1', { method: 'DELETE' }), undefined)
  await assert.rejects(api.json('/auth/me'), /登录已失效/)
  assert.equal(expired, 1)
})
test('上传不覆盖 multipart boundary，429 与 403 返回可读错误', async t => {
  const api = createApi('test', () => {})
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(init.headers.has('Content-Type'), false)
    return Response.json({}, { status: url.endsWith('/limited') ? 429 : 403 })
  })
  await assert.rejects(api.json('/limited', { method: 'POST', body: new FormData() }), /频繁/)
  await assert.rejects(api.json('/forbidden'), /权限/)
})
