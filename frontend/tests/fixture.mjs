import { createServer } from 'node:http'

// 仅用于独立的浏览器交互验收，不访问真实数据库或付费模型。
let bases = [{ id: 1, name: '产品研发知识库', ownerId: 1, role: 'OWNER' }, { id: 2, name: '团队协作手册', ownerId: 2, role: 'VIEWER' }]
let docs = [{ id: 1, name: '研发交付规范.md', status: 'READY', latestTaskStatus: 'SUCCEEDED', latestTaskStage: 'INDEXED', indexVersion: 1, activeIndexVersion: 1, sizeBytes: 3400, updatedAt: '2026-09-04T12:00:00' }, { id: 2, name: '新成员入职指南.pdf', status: 'READY', latestTaskStatus: 'SUCCEEDED', indexVersion: 1, activeIndexVersion: 1, sizeBytes: 58200, updatedAt: '2026-09-04T11:30:00' }]
const source = { id: 'C1', documentId: 1, chunkId: 10, documentName: '研发交付规范.md', pageNumber: null, paragraphNumber: 2, quote: '每次发布前需完成代码审查和自动化测试，并由负责人确认回滚方案。' }
let turns = []
createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost')
  let raw = ''; for await (const chunk of req) raw += chunk
  const data = req.headers['content-type']?.includes('application/json') && raw ? JSON.parse(raw) : {}
  const json = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)) }
  if (url.pathname === '/api/auth/register') return json({ id: 1 }, 201)
  if (url.pathname === '/api/auth/login') return json({ accessToken: 'local-fixture-token' })
  if (url.pathname === '/api/auth/me') return json({ id: 1, username: 'ui_test', role: 'USER' })
  if (url.pathname === '/api/knowledge-bases') {
    if (req.method === 'POST') { const base = { id: bases.length + 1, name: data.name, ownerId: 1, role: 'OWNER' }; bases.push(base); return json(base, 201) }
    return json(bases)
  }
  const baseId = Number(url.pathname.match(/knowledge-bases\/(\d+)/)?.[1])
  if (/\/knowledge-bases\/\d+$/.test(url.pathname)) { const base = bases.find(item => item.id === baseId); base.name = data.name; return json(base) }
  if (url.pathname.endsWith('/members')) return json([{ userId: 1, username: 'ui_test', role: 'OWNER', status: 'ACTIVE' }])
  if (/\/members\/\d+$/.test(url.pathname)) return json({ detail: '目标用户不存在或不可用' }, 404)
  if (url.pathname.endsWith('/documents')) {
    if (req.method === 'POST') { docs.push({ ...docs[0], id: docs.length + 1, name: '浏览器上传测试.txt', status: 'PENDING', latestTaskStatus: 'PROCESSING' }); return json({ taskId: 7 }, 202) }
    return json(baseId === 1 ? docs : [])
  }
  if (url.pathname.endsWith('/reindex')) return json({ taskId: 7 }, 202)
  if (/\/documents\/\d+$/.test(url.pathname)) return json({ ...docs.find(doc => doc.id === Number(url.pathname.split('/').at(-1))), status: 'READY', latestTaskStatus: 'SUCCEEDED' })
  if (url.pathname === '/api/conversations') return json(turns.length ? [{ id: 12, knowledgeBaseId: 1, createdAt: '2026-09-04T12:00:00' }] : [])
  if (url.pathname.endsWith('/messages')) return json(turns)
  if (url.pathname.endsWith('/search')) {
    if (data.query === '过期') return json({}, 401)
    res.setHeader('X-Rerank-Status', data.rerank ? 'DISABLED' : 'NOT_REQUESTED')
    return json([{ ...source, content: source.quote, score: 0.023 }])
  }
  if (url.pathname.endsWith('/answers/stream')) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    const event = (name, value) => res.write(`event: ${name}\r\ndata: ${JSON.stringify(value)}\r\n\r\n`)
    event('metadata', { conversationId: 12, messageId: 2 })
    event('delta', { text: '发布前需要完成代码审查和自动化测试。' })
    if (data.question.includes('中断')) return res.end()
    if (data.question.includes('撤回')) { event('error', { discard: true }); return res.end() }
    const timer = setTimeout(() => {
      const answer = (data.conversationId ? '根据刚才的讨论，' : '') + '发布前需要完成代码审查、自动化测试，并确认回滚方案。[C1]'
      event('citation', source); event('done', { status: 'ANSWERED', answer })
      turns.push({ id: turns.length + 1, role: 'USER', status: 'COMPLETED', content: data.question, redacted: false, citations: [] }, { id: turns.length + 2, role: 'ASSISTANT', status: 'COMPLETED', content: answer, redacted: false, citations: [source] })
      res.end()
    }, data.question.includes('缓慢') ? 30000 : 200)
    res.on('close', () => clearTimeout(timer)); return
  }
  json({ detail: '测试接口未实现' }, 404)
}).listen(18080, '127.0.0.1', () => console.log('UI fixture: http://127.0.0.1:18080'))
