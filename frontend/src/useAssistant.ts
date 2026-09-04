import { onMounted, onUnmounted, shallowRef } from 'vue'
import type { Api } from './api'
import { errorText } from './api'
import type { Conversation, Hit, Message, Source } from './types'
import { readAnswerStream } from './stream'
import { useScope } from './useScope'

export function useAssistant(api: Api, baseId: number) {
  const { signal, error, busy, run } = useScope()
  const history = shallowRef<Conversation[]>([]), messages = shallowRef<Message[]>([]), hits = shallowRef<Hit[]>([])
  const conversationId = shallowRef<number>(), answering = shallowRef(false), searched = shallowRef(false)
  const messageMore = shallowRef(false), historyMore = shallowRef(false), rerankStatus = shallowRef('')
  let historyCursor = 0, generation = 0, abort: AbortController | undefined
  const path = `/knowledge-bases/${baseId}`
  async function loadHistory(append = false) {
    const page = await api.json<Conversation[]>(`/conversations?limit=100&afterId=${append ? historyCursor : 0}`, { signal })
    signal.throwIfAborted(); historyCursor = page.at(-1)?.id ?? historyCursor; historyMore.value = page.length === 100
    const scoped = page.filter(item => item.knowledgeBaseId === baseId)
    history.value = append ? [...history.value, ...scoped] : scoped
  }
  function reset() { generation++; abort?.abort(); answering.value = false; messages.value = []; conversationId.value = undefined; error.value = ''; messageMore.value = false }
  function open(id: number) {
    if (busy.value) return
    reset(); conversationId.value = id
    void run(async () => { const page = await api.json<Message[]>(`/conversations/${id}/messages?limit=100`, { signal }); signal.throwIfAborted(); messages.value = page; messageMore.value = page.length === 100 })
  }
  function moreMessages() { void run(async () => { const page = await api.json<Message[]>(`/conversations/${conversationId.value}/messages?limit=100&afterId=${messages.value.at(-1)?.id ?? 0}`, { signal }); signal.throwIfAborted(); messages.value = [...messages.value, ...page]; messageMore.value = page.length === 100 }) }
  async function ask(question: string) {
    if (answering.value || busy.value || !question.trim() || messageMore.value) return
    const version = ++generation
    const local = new AbortController(); abort = local; answering.value = true; error.value = ''
    const responseId = -Date.now()
    messages.value = [...messages.value, { id: responseId - 1, role: 'USER', content: question, status: 'COMPLETED', redacted: false, citations: [] }, { id: responseId, role: 'ASSISTANT', content: '', status: 'RUNNING', redacted: false, citations: [] }]
    const patch = (values: Partial<Message>) => { if (!signal.aborted && version === generation) messages.value = messages.value.map(message => message.id === responseId ? { ...message, ...values } : message) }
    const sources: Source[] = []
    let text = '', discarded = false
    try {
      const response = await api.raw(`${path}/answers/stream`, { method: 'POST', body: JSON.stringify({ question, topK: 4, mode: 'HYBRID', rerank: false, conversationId: conversationId.value, rewrite: !!conversationId.value }), signal: AbortSignal.any([signal, local.signal, AbortSignal.timeout(150000)]) })
      if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('服务未返回流式回答')
      await readAnswerStream(response.body, ({ event, data }) => {
        if (signal.aborted || version !== generation) return
        if (event === 'metadata' && typeof data.conversationId === 'number') conversationId.value = data.conversationId
        if (event === 'delta') { if (typeof data.text !== 'string') throw new Error('回答内容格式无效'); text += data.text; patch({ content: text }) }
        if (event === 'citation') sources.push(data as unknown as Source)
        if (event === 'done') patch({ content: data.answer as string, status: 'COMPLETED', citations: sources })
        if (event === 'error' && data.discard) { discarded = true; patch({ content: null, citations: [], redacted: true, status: 'FAILED' }) }
      })
    } catch (cause) {
      if (!signal.aborted && version === generation) {
        patch({ status: local.signal.aborted ? 'CANCELLED' : 'FAILED', citations: [], content: discarded ? null : text, redacted: discarded })
        error.value = local.signal.aborted ? '已停止生成，保留的内容尚未完成。' : errorText(cause)
      }
    } finally {
      if (!signal.aborted && version === generation) {
        answering.value = false; abort = undefined
        if (conversationId.value && !history.value.some(item => item.id === conversationId.value)) history.value = [...history.value, { id: conversationId.value, knowledgeBaseId: baseId, createdAt: new Date().toISOString() }]
      }
    }
  }
  function search(query: string, mode: string, rerank: boolean) {
    void run(async () => {
      hits.value = []; searched.value = false
      const response = await api.raw(`${path}/search`, { method: 'POST', body: JSON.stringify({ query, mode, rerank: mode === 'HYBRID' && rerank, topK: 8 }), signal })
      const result = await response.json(); signal.throwIfAborted(); hits.value = result; searched.value = true; rerankStatus.value = response.headers.get('X-Rerank-Status') ?? 'UNKNOWN'
    })
  }
  onMounted(() => run(() => loadHistory()))
  onUnmounted(() => { generation++; abort?.abort() })
  return { history, messages, conversationId, answering, searched, hits, error, busy, run, reset, open, ask, search, moreMessages, messageMore, historyMore, loadHistory, rerankStatus, stop: () => abort?.abort() }
}
