import assert from 'node:assert/strict'
import { writeFile } from 'node:fs/promises'
import { setTimeout as delay } from 'node:timers/promises'

// 真实联调会消耗模型额度；通过应用预算保护执行，结果只代表本组样例。
const origin = process.env.KNOWFLOW_URL ?? 'http://127.0.0.1:8088'
let token
async function call(path, body, extra = {}) {
  const headers = { ...extra }
  if (token) headers.Authorization = `Bearer ${token}`
  if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; body = JSON.stringify(body) }
  const started = performance.now()
  const response = await fetch(`${origin}/api${path}`, { method: body ? 'POST' : 'GET', headers, body, signal: AbortSignal.timeout(45000) })
  const data = await response.json()
  assert.ok(response.ok, `${path}: ${response.status} ${data.detail ?? ''}`)
  return { data, elapsedMs: performance.now() - started, headers: response.headers }
}
const credentials = { username: `live_${crypto.randomUUID().replaceAll('-', '')}`, password: `Live_${crypto.randomUUID()}` }
await call('/auth/register', credentials)
token = (await call('/auth/login', credentials)).data.accessToken
const base = (await call('/knowledge-bases', { name: '真实重排与改写联调' })).data
const path = `/knowledge-bases/${base.id}`
const documents = [
  ['差旅报销.md', '差旅报销流程\n\n员工提交差旅费用报销单和发票，差旅报销由直属主管审批。差旅报销审批应在三个工作日内完成。'],
  ['采购申请.md', '采购申请流程\n\n采购申请必须附供应商报价。采购审批由财务总监负责。采购审批需要五个工作日。'],
  ['知识库权限.md', '知识库权限申请\n\n员工联系知识库所有者申请权限。所有者可授予 VIEWER 或 EDITOR。权限申请在一个工作日内处理。']
]
const indexed = []
for (const [name, content] of documents) {
  const form = new FormData(); form.append('file', new Blob([content], { type: 'text/markdown' }), name)
  const upload = (await call(`${path}/documents`, form, { 'Idempotency-Key': crypto.randomUUID() })).data
  let task
  for (let i = 0; i < 180; i++) {
    task = (await call(`/document-tasks/${upload.taskId}`)).data
    assert.notEqual(task.status, 'FAILED', task.errorCode)
    if (task.status === 'SUCCEEDED') break
    await delay(1000)
  }
  assert.equal(task.status, 'SUCCEEDED'); indexed.push({ name, content, ...upload })
}
async function search(query, rerank) {
  const result = await call(`${path}/search`, { query, topK: 5, mode: 'HYBRID', rerank })
  return { query, elapsedMs: result.elapsedMs, rerankStatus: result.headers.get('X-Rerank-Status'), hits: result.data }
}
const question = '差旅报销由谁审批？'
const rrf = await search(question, false), reranked = await search(question, true)
await writeFile(new URL('../target/live-retrieval-check.json', import.meta.url), JSON.stringify({ stage: 'RERANK', baseline: rrf, requested: reranked }, null, 2))
assert.equal(reranked.rerankStatus, 'APPLIED', '真实重排未生效，已保存结果并停止后续付费调用')
const seed = await call(`${path}/answers`, { question, topK: 3, mode: 'HYBRID', rerank: false })
const conversationId = Number(seed.headers.get('X-Conversation-Id'))
assert.ok(conversationId && seed.data.citations.length)
const followup = '这项审批多久能完成？'
const original = await search(followup, false)
const answer = await call(`${path}/answers`, { question: followup, conversationId, rewrite: true, topK: 3, mode: 'HYBRID', rerank: false })
const history = (await call(`/conversations/${conversationId}/messages`)).data
const message = history.find(item => item.id === Number(answer.headers.get('X-Message-Id')))
assert.ok(message)
const rewritten = await search(message.retrievalQuery, false)
const expectedDocumentId = indexed[0].documentId
const rank = result => { const index = result.hits.findIndex(hit => hit.documentId === expectedDocumentId && hit.content.includes('三个工作日')); return index < 0 ? null : index + 1 }
const report = {
  generatedAt: new Date().toISOString(), knowledgeBaseId: base.id, documents: indexed,
  rerank: { applied: reranked.rerankStatus === 'APPLIED', baseline: rrf, requested: reranked },
  rewrite: { status: message.rewriteStatus, originalQuestion: followup, retrievalQuery: message.retrievalQuery, seed: seed.data, answer: answer.data, baseline: original, rewritten, baselineRelevantRank: rank(original), rewrittenRelevantRank: rank(rewritten) },
  limitation: '三篇合成文档、一次追问的真实联调；相同原始问题和记录的改写问题分别检索，不代表完整数据集质量提升。'
}
await writeFile(new URL('../target/live-retrieval-check.json', import.meta.url), JSON.stringify(report, null, 2))
console.log(JSON.stringify({ rerankApplied: report.rerank.applied, rewriteStatus: message.rewriteStatus, retrievalQuery: message.retrievalQuery, baselineRelevantRank: rank(original), rewrittenRelevantRank: rank(rewritten), report: 'target/live-retrieval-check.json' }, null, 2))
assert.equal(report.rerank.applied, true, '真实重排未生效，已保留退回信息')
assert.equal(message.rewriteStatus, 'APPLIED', '未得到真实有效改写，已保留实际结果')
