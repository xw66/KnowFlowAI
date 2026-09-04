import test from 'node:test'
import assert from 'node:assert/strict'
import { readAnswerStream } from '../src/stream.ts'

function body(text, chunkSize = 1) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i += chunkSize) controller.enqueue(bytes.slice(i, i + chunkSize)); controller.close() } })
}
test('中文跨字节、CRLF、心跳、多行 data、最终回答均正确解析', async () => {
  const events = []
  await readAnswerStream(body(': heartbeat\r\n\r\nevent: delta\r\ndata: {"text":"中文"}\r\n\r\nevent: done\r\ndata: {"status":"ANSWERED",\r\ndata: "answer":"最终正文"}\r\n\r\n'), event => events.push(event))
  assert.equal(events[0].data.text, '中文')
  assert.equal(events[1].data.answer, '最终正文')
})
test('提前断流不视为完成', async () => {
  await assert.rejects(readAnswerStream(body('event: delta\ndata: {"text":"部分"}\n\n'), () => {}), /尚未完成/)
})
test('权限撤回事件先通知界面清除来源，然后失败', async () => {
  let discarded = false
  await assert.rejects(readAnswerStream(body('event: error\ndata: {"discard":true}\n\n'), event => discarded = event.data.discard), /权限或内容已变更/)
  assert.equal(discarded, true)
})
test('异常或未知完成状态不能冒充有效答案', async () => {
  await assert.rejects(readAnswerStream(body('event: done\ndata: {"status":"FAILED","answer":"错误"}\n\n'), () => {}), /结束状态无效/)
})
test('正常结束主动关闭 reader，避免继续消费后续内容', async () => {
  let cancelled = false
  const stream = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('event: done\ndata: {"status":"INSUFFICIENT_EVIDENCE","answer":"没有证据"}\n\n')) }, cancel() { cancelled = true } })
  await readAnswerStream(stream, () => {})
  assert.equal(cancelled, true)
})
test('上游取消以错误结束，绝不伪造 done', async () => {
  const stream = new ReadableStream({ start(c) { c.error(new DOMException('已取消', 'AbortError')) } })
  await assert.rejects(readAnswerStream(stream, () => {}), { name: 'AbortError' })
})
