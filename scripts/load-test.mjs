import { mkdir, writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
import process from 'node:process'

const baseUrl = (process.env.KNOWFLOW_LOAD_BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '')
const concurrencyLevels = (process.env.KNOWFLOW_LOAD_CONCURRENCY || '1,5,10').split(',').map(Number).filter(Number.isInteger)
const selectedKinds = new Set((process.env.KNOWFLOW_LOAD_KINDS || 'search,sse,ingest').split(',').map(value => value.trim()).filter(Boolean))
const modelLabel = process.env.KNOWFLOW_LOAD_MODEL || 'LOCAL_PROTOCOL_FIXTURE'

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}/api${path}`, options)
  const text = await response.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { /* 返回体不是 JSON 时只保留状态码 */ }
  return { response, text, data }
}

async function setup() {
  const credentials = { username: `load_${crypto.randomUUID().replaceAll('-', '')}`, password: `Load_${crypto.randomUUID().replaceAll('-', '')}` }
  const registered = await request('/auth/register', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(credentials) })
  if (registered.response.status !== 201) throw new Error(`注册失败：${registered.response.status}`)
  const login = await request('/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(credentials) })
  if (login.response.status !== 200) throw new Error(`登录失败：${login.response.status}`)
  const headers = { authorization: `Bearer ${login.data.accessToken}` }
  const knowledgeBase = await request('/knowledge-bases', { method: 'POST', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify({ name: 'KnowFlow 压测' }) })
  if (knowledgeBase.response.status !== 201) throw new Error(`建库失败：${knowledgeBase.response.status}`)
  const form = new FormData()
  form.append('file', new Blob(['# 压测文档\n\n知识库压测使用本地协议替身。'], { type: 'text/markdown' }), 'load.md')
  const upload = await request(`/knowledge-bases/${knowledgeBase.data.id}/documents`, { method: 'POST', headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() }, body: form })
  if (upload.response.status !== 202) throw new Error(`上传失败：${upload.response.status}`)
  for (let i = 0; i < 180; i++) {
    const task = await request(`/document-tasks/${upload.data.taskId}`, { headers })
    if (task.data?.status === 'SUCCEEDED') return { id: knowledgeBase.data.id, headers }
    if (task.data?.status === 'FAILED') throw new Error('索引任务失败')
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error('索引任务超时')
}

async function runRequests(kind, count, concurrency, context) {
  const samples = []
  const batchStarted = performance.now()
  let cursor = 0
  async function worker() {
    while (true) {
      const index = cursor++
      if (index >= count) return
      const started = performance.now()
      let status = 599
      try {
        if (kind === 'ingest') {
          const form = new FormData()
          form.append('file', new Blob([`# 异步压测 ${index}\n\n验证 Kafka 任务处理。`], { type: 'text/markdown' }), `load-${index}.md`)
          const upload = await request(`/knowledge-bases/${context.id}/documents`, { method: 'POST', headers: { ...context.headers, 'Idempotency-Key': crypto.randomUUID() }, body: form })
          status = upload.response.status
          if (status === 202) {
            for (let i = 0; i < 180; i++) {
              const task = await request(`/document-tasks/${upload.data.taskId}`, { headers: context.headers })
              if (task.data?.status === 'SUCCEEDED') break
              if (task.data?.status === 'FAILED') { status = 599; break }
              await new Promise(resolve => setTimeout(resolve, 1000))
            }
          }
        } else {
          const path = kind === 'search' ? `/knowledge-bases/${context.id}/search` : `/knowledge-bases/${context.id}/answers/stream`
          const body = kind === 'search'
            ? { query: '压测知识库', topK: 5, mode: 'BM25', rerank: false }
            : { question: '压测知识库', topK: 3, mode: 'HYBRID', rerank: false }
          const result = await request(path, { method: 'POST', headers: { ...context.headers, 'content-type': 'application/json' }, body: JSON.stringify(body) })
          status = result.response.status
        }
      } catch { /* 将网络异常计入错误率 */ }
      samples.push({ durationMs: performance.now() - started, status })
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker))
  return summarize(kind, count, concurrency, samples, performance.now() - batchStarted)
}

function percentile(values, fraction) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * fraction) - 1)]
}

function summarize(kind, count, concurrency, samples, elapsedMs) {
  const durations = samples.map(sample => sample.durationMs)
  const errors = samples.filter(sample => sample.status < 200 || sample.status >= 300).length
  const totalMs = durations.reduce((sum, value) => sum + value, 0)
  return { kind, concurrency, requests: count, errors, errorRate: errors / count, throughputPerSecond: count / (Math.max(elapsedMs, 1) / 1000), p50Ms: percentile(durations, .5), p95Ms: percentile(durations, .95), p99Ms: percentile(durations, .99), meanMs: totalMs / count, wallTimeMs: elapsedMs }
}

const context = await setup()
const results = []
for (const concurrency of concurrencyLevels) {
  if (selectedKinds.has('search')) results.push(await runRequests('search', concurrency * 3, concurrency, context))
  if (selectedKinds.has('sse')) results.push(await runRequests('sse', concurrency * 3, concurrency, context))
  if (selectedKinds.has('ingest')) results.push(await runRequests('ingest', concurrency, concurrency, context))
}
await mkdir('target', { recursive: true })
const report = { generatedAt: new Date().toISOString(), baseUrl, model: modelLabel, concurrencyLevels, selectedKinds: [...selectedKinds], results, limitation: modelLabel === 'LOCAL_PROTOCOL_FIXTURE' ? '当前使用协议替身；结果仅验证本地协议和任务链路，不代表真实模型性能。' : '真实模型低并发基线；不代表高并发容量结论。' }
await writeFile('target/load-test.json', JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2))
