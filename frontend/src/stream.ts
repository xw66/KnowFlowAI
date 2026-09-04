export interface StreamEvent { event: string; data: Record<string, unknown> }

// SSE 的 UTF-8 字符和换行都可能跨网络分片，只有完整空行才提交事件。
export async function readAnswerStream(body: ReadableStream<Uint8Array>, receive: (event: StreamEvent) => void) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = '', completed = false
  try {
    while (!completed) {
      const { value, done } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      if (buffer.length > 131072) throw new Error('回答事件过大，请重试')
      let boundary: RegExpExecArray | null
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const block = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        const lines = block.split(/\r?\n/)
        const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
        const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n')
        if (!event || !data) continue
        const parsed = JSON.parse(data)
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('回答事件格式无效')
        if (event === 'done' && (!['ANSWERED','INSUFFICIENT_EVIDENCE'].includes(parsed.status) || typeof parsed.answer !== 'string')) throw new Error('回答结束状态无效')
        receive({ event, data: parsed })
        if (event === 'error') throw new Error(parsed.discard ? '来源权限或内容已变更，回答已撤回' : '回答中断，以下内容尚未完成')
        if (event === 'done') { completed = true; break }
      }
      if (done) break
    }
    if (!completed) throw new Error('连接提前结束，回答尚未完成，请重试')
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
}
