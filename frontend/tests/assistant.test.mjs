import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { createServer } from 'vite'

const server = await createServer({ configFile: false, server: { middlewareMode: true }, appType: 'custom' })
after(() => server.close())
const { useAssistant } = await server.ssrLoadModule('/src/useAssistant.ts')
const renderer = createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null, nextSibling: () => null })
async function mount(t, raw, base = 1) {
  let state
  const app = renderer.createApp({ setup() { state = useAssistant({ raw, json: async () => [] }, base); return () => null } })
  app.mount({}); t.after(() => app.unmount())
  await new Promise(resolve => setImmediate(resolve))
  return { state, app }
}
function response(events) {
  return new Response(events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join(''), { headers: { 'content-type': 'text/event-stream' } })
}

test('完成后保存引用与会话，续问保持知识库范围', async t => {
  const calls = []
  const { state } = await mount(t, async (path, init) => {
    calls.push({ path, body: JSON.parse(init.body) })
    return response([['metadata', { conversationId: 7 }], ['citation', { documentName: '原文', paragraphNumber: 2 }], ['done', { status: 'ANSWERED', answer: '有据回答' }]])
  })
  await state.ask('问题'); await state.ask('继续')
  assert.equal(state.messages.value.at(-1).status, 'COMPLETED')
  assert.equal(state.messages.value.at(-1).citations.length, 1)
  assert.equal(calls[1].path, '/knowledge-bases/1/answers/stream')
  assert.equal(calls[1].body.conversationId, 7)
  assert.equal(calls[1].body.rewrite, true)
})

test('权限撤回清空正文和引用，提前断流保留未完成标记', async t => {
  let discard = true
  const { state } = await mount(t, async () => response([
    ['delta', { text: '部分正文' }], ['citation', { documentName: '原文' }],
    ...(discard ? [['error', { discard: true }]] : [])
  ]))
  await state.ask('问题')
  assert.deepEqual([state.messages.value.at(-1).content, state.messages.value.at(-1).redacted, state.messages.value.at(-1).citations], [null, true, []])
  discard = false; state.reset(); await state.ask('新问题')
  assert.equal(state.messages.value.at(-1).content, '部分正文')
  assert.equal(state.messages.value.at(-1).status, 'FAILED')
  assert.deepEqual(state.messages.value.at(-1).citations, [])
})

test('重复发送只发一次，停止生成取消上游', async t => {
  let count = 0, signal
  const { state } = await mount(t, (_path, init) => new Promise((_resolve, reject) => {
    count++; signal = init.signal; signal.addEventListener('abort', () => reject(signal.reason), { once: true })
  }))
  const pending = state.ask('问题'); await state.ask('重复问题'); state.stop(); await pending
  assert.equal(count, 1); assert.equal(signal.aborted, true)
  assert.equal(state.messages.value.at(-1).status, 'CANCELLED')
  assert.equal(state.answering.value, false)
})

test('切换知识库卸载后取消旧请求，迟到响应不能写入新会话', async t => {
  let finish, signal
  const old = await mount(t, (_path, init) => { signal = init.signal; return new Promise(resolve => { finish = resolve }) })
  const pending = old.state.ask('旧库问题'); old.app.unmount()
  const current = await mount(t, async () => response([]), 2)
  finish(response([['metadata', { conversationId: 99 }], ['done', { status: 'ANSWERED', answer: '旧库正文' }]]))
  await pending
  assert.equal(signal.aborted, true)
  assert.equal(current.state.conversationId.value, undefined)
  assert.deepEqual(current.state.messages.value, [])
  assert.notEqual(old.state.messages.value.at(-1).content, '旧库正文')
})
