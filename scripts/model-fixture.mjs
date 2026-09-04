import { createServer } from 'node:http'
import { setTimeout as delay } from 'node:timers/promises'

// 仅验证部署协议；固定向量和固定答案不得参与真实模型效果或费用结论。
createServer(async (req, res) => {
  try {
    if (req.url === '/health') { res.end('ok'); return }
    let text = ''
    for await (const chunk of req) { text += chunk; if (text.length > 1000000) throw new Error('request too large') }
    const body = JSON.parse(text)
    res.setHeader('Content-Type', 'application/json')
    if (req.url === '/v1/embeddings') {
      res.end(JSON.stringify({ object: 'list', model: 'fixture-embedding', usage: { prompt_tokens: 0, total_tokens: 0 }, data: body.input.map((_, index) => ({ object: 'embedding', index, embedding: [1, 0, 0] })) })); return
    }
    if (req.url !== '/v1/chat/completions') { res.statusCode = 404; res.end('{}'); return }
    const evidence = JSON.parse(body.messages.at(-1).content).evidence[0]
    const answer = '部署协议验证回答 [C1]'
    if (body.stream) {
      res.setHeader('Content-Type', 'text/event-stream')
      const send = (content, finish_reason = null) => res.write(`data: ${JSON.stringify({ id: 'fixture', object: 'chat.completion.chunk', created: 1, model: 'fixture-chat', choices: [{ index: 0, delta: { role: 'assistant', content }, finish_reason }] })}\n\n`)
      send('部署协议'); await delay(1000); if (res.destroyed) return
      send('验证回答 [C1]'); send('', 'stop'); res.end('data: [DONE]\n\n'); return
    }
    res.end(JSON.stringify({ id: 'fixture', object: 'chat.completion', created: 1, model: 'fixture-chat', choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify({ answer, citations: [{ id: 'C1', quote: evidence.content }] }) }, finish_reason: 'stop' }] }))
  } catch { res.statusCode = 400; res.end('{}') }
}).listen(8000, '0.0.0.0')
