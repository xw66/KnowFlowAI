import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { setTimeout as delay } from 'node:timers/promises'

const origin = 'http://127.0.0.1:18088'
const resume = process.argv.includes('--resume')
const statePath = new URL('../target/compose-acceptance-state.json', import.meta.url)
let token
async function request(path, body, method = body ? 'POST' : 'GET') {
  const headers = token ? { Authorization: `Bearer ${token}` } : {}
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body) }
  const response = await fetch(`${origin}/api${path}`, { method, headers, body, signal: AbortSignal.timeout(30000) })
  assert.equal(response.ok, true, `${path}: ${response.status}`)
  return response.json()
}
assert.equal((await fetch(origin)).status, 200)
assert.equal((await fetch(`${origin}/swagger-ui/index.html`)).status, 200)
let state = resume ? JSON.parse(await readFile(statePath, 'utf8')) : { username: `deploy_${crypto.randomUUID().replaceAll('-', '')}`, password: `Deploy_${crypto.randomUUID()}` }
if (!resume) await request('/auth/register', { username: state.username, password: state.password })
token = (await request('/auth/login', { username: state.username, password: state.password })).accessToken
if (!resume) {
  state.baseId = (await request('/knowledge-bases', { name: '隔离部署验收' })).id
  const form = new FormData()
  form.append('file', new Blob([await readFile(new URL('../docs/demo.md', import.meta.url))], { type: 'text/markdown' }), 'demo.md')
  const response = await fetch(`${origin}/api/knowledge-bases/${state.baseId}/documents`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': crypto.randomUUID() }, body: form })
  assert.equal(response.status, 202)
  const upload = await response.json(); state.taskId = upload.taskId; state.documentId = upload.documentId
}
let task
for (let attempt = 0; attempt < 180; attempt++) {
  task = await request(`/document-tasks/${state.taskId}`)
  assert.notEqual(task.status, 'FAILED', task.errorCode)
  if (task.status === 'SUCCEEDED') break
  await delay(1000)
}
assert.equal(task.status, 'SUCCEEDED')
assert.ok((await request('/knowledge-bases')).some(base => base.id === state.baseId))
assert.ok((await request(`/knowledge-bases/${state.baseId}/documents`)).some(doc => doc.id === state.documentId))
for (const mode of ['VECTOR', 'BM25', 'HYBRID']) {
  let hits = []
  for (let attempt = 0; attempt < 20 && !hits.length; attempt++) {
    hits = await request(`/knowledge-bases/${state.baseId}/search`, { query: '知识库访问权限', topK: 5, mode, rerank: false })
    if (!hits.length) await delay(1000)
  }
  assert.ok(hits.some(hit => hit.documentId === state.documentId && hit.content))
}
if (!resume) {
  const start = performance.now()
  const response = await fetch(`${origin}/api/knowledge-bases/${state.baseId}/answers/stream`, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ question: '如何申请知识库访问权限？', topK: 3, mode: 'HYBRID', rerank: false }), signal: AbortSignal.timeout(30000) })
  assert.equal(response.status, 200)
  const reader = response.body.getReader(), decoder = new TextDecoder()
  let buffer = '', firstDelta, doneAt, citations = 0
  while (true) {
    const chunk = await reader.read(); if (chunk.done) break
    buffer += decoder.decode(chunk.value, { stream: true })
    let boundary
    while ((boundary = buffer.match(/\r?\n\r?\n/))) {
      const block = buffer.slice(0, boundary.index); buffer = buffer.slice(boundary.index + boundary[0].length)
      const event = block.match(/^event:\s*(.+)$/m)?.[1]?.trim()
      if (!event) continue
      const data = JSON.parse(block.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n'))
      assert.notEqual(event, 'error', JSON.stringify(data))
      if (event === 'metadata') state.conversationId = data.conversationId
      if (event === 'delta') firstDelta ??= performance.now() - start
      if (event === 'citation') citations++
      if (event === 'done') { doneAt = performance.now() - start; assert.equal(data.status, 'ANSWERED') }
    }
  }
  assert.ok(citations > 0 && state.conversationId)
  assert.ok(Number.isFinite(firstDelta) && doneAt - firstDelta >= 500, '代理必须在上游结束前返回正文片段')
  state.proxyStreaming = { firstDeltaMs: firstDelta, doneMs: doneAt, fixtureDelayMs: 1000 }
  await writeFile(statePath, JSON.stringify(state))
}
const history = await request(`/conversations/${state.conversationId}/messages`)
assert.ok(history.some(message => message.role === 'ASSISTANT' && message.status === 'COMPLETED' && message.citations.length > 0))
const report = { phase: resume ? 'RESTART' : 'FRESH', result: 'PASS', model: 'LOCAL_PROTOCOL_FIXTURE', taskStatus: task.status, modes: ['VECTOR', 'BM25', 'HYBRID'], conversationRestored: true, proxyStreaming: state.proxyStreaming }
await writeFile(new URL(`../target/compose-${resume ? 'restart' : 'fresh'}-result.json`, import.meta.url), JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2))
