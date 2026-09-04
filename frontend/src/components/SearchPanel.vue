<script setup lang="ts">
import { onMounted, reactive, shallowRef } from 'vue'

interface Hit { documentName: string; paragraphNumber?: number; pageNumber?: number; score: number; content: string }
interface Citation { documentName: string; paragraphNumber?: number; pageNumber?: number; quote: string }
interface DocumentItem { id: number; name: string; status: string; latestTaskStatus: string; latestTaskStage?: string; errorCode?: string; indexVersion: number }
interface Conversation { id: number; knowledgeBaseId: number; createdAt: string }
interface Member { userId: number; username: string; role: string; status: string }
interface KnowledgeBase { id: number; name: string; role: string }
const props = defineProps<{ token: string }>()
const emit = defineEmits<{ logout: [] }>()
const form = reactive({ knowledgeBaseId: '', query: '', topK: 5, mode: 'HYBRID', rerank: false })
const hits = shallowRef<Hit[]>([]); const busy = shallowRef(false); const error = shallowRef('')
const selectedFile = shallowRef<File>(); const taskStatus = shallowRef(''); const answerQuestion = shallowRef(''); const answerText = shallowRef(''); const citations = shallowRef<Citation[]>([]); const answering = shallowRef(false)
const documents = shallowRef<DocumentItem[]>([]); const conversations = shallowRef<Conversation[]>([])
const members = shallowRef<Member[]>([]); const memberForm = reactive({ userId: '', role: 'VIEWER' })
const knowledgeBases = shallowRef<KnowledgeBase[]>([]); const newKnowledgeBaseName = shallowRef(''); const creatingKnowledgeBase = shallowRef(false)
const documentAction = shallowRef<number | null>(null)
let answerAbortController: AbortController | undefined

async function loadKnowledgeBases() {
  const response = await fetch('/api/knowledge-bases?limit=100', { headers: { Authorization: `Bearer ${props.token}` } })
  if (response.status === 401) { emit('logout'); return }
  if (!response.ok) throw new Error('知识库列表读取失败')
  knowledgeBases.value = await response.json()
  if (!knowledgeBases.value.some(base => String(base.id) === form.knowledgeBaseId)) form.knowledgeBaseId = knowledgeBases.value[0] ? String(knowledgeBases.value[0].id) : ''
}
async function createKnowledgeBase() {
  if (!newKnowledgeBaseName.value.trim()) return
  creatingKnowledgeBase.value = true; error.value = ''
  try {
    const response = await fetch('/api/knowledge-bases', { method: 'POST', headers: { Authorization: `Bearer ${props.token}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ name: newKnowledgeBaseName.value.trim() }) })
    if (!response.ok) throw new Error('知识库创建失败')
    const created = await response.json(); newKnowledgeBaseName.value = ''; await loadKnowledgeBases(); form.knowledgeBaseId = String(created.id); await loadMembers()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '知识库创建失败' }
  finally { creatingKnowledgeBase.value = false }
}

async function search() {
  busy.value = true; error.value = ''; hits.value = []
  try {
    const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/search`, { method:'POST', headers:{'Content-Type':'application/json', Authorization:`Bearer ${props.token}`}, body:JSON.stringify({query:form.query,topK:form.topK,mode:form.mode,rerank:form.rerank}) })
    if (response.status === 401) { emit('logout'); return }
    if (!response.ok) throw new Error('检索失败，请确认知识库权限和索引状态')
    hits.value = await response.json()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '请求失败' }
  finally { busy.value = false }
}

async function loadDocuments() {
  if(!form.knowledgeBaseId) return
  const response=await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/documents`,{headers:{Authorization:`Bearer ${props.token}`}})
  if(response.status===401){emit('logout');return} if(!response.ok) throw new Error('文档列表读取失败'); documents.value=await response.json()
}
async function loadConversations() {
  const response=await fetch('/api/conversations?limit=20',{headers:{Authorization:`Bearer ${props.token}`}})
  if(!response.ok) throw new Error('会话历史读取失败'); conversations.value=await response.json()
}
async function loadMembers() {
  if(!form.knowledgeBaseId) return
  const response=await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/members?limit=50`,{headers:{Authorization:`Bearer ${props.token}`}})
  if(!response.ok) throw new Error('成员列表读取失败'); members.value=await response.json()
}
async function saveMember() {
  const response=await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/members/${encodeURIComponent(memberForm.userId)}`,{method:'PUT',headers:{Authorization:`Bearer ${props.token}`,'Content-Type':'application/json'},body:JSON.stringify({role:memberForm.role})})
  if(!response.ok) throw new Error('成员授权失败'); await loadMembers(); memberForm.userId=''
}
async function removeMember(userId:number) {
  const response=await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/members/${userId}`,{method:'DELETE',headers:{Authorization:`Bearer ${props.token}`}})
  if(!response.ok) throw new Error('成员移除失败'); await loadMembers()
}

async function reindex(documentId: number) {
  documentAction.value = documentId; error.value = ''
  try {
    const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/documents/${documentId}/reindex`, { method: 'POST', headers: { Authorization: `Bearer ${props.token}`, 'Idempotency-Key': crypto.randomUUID() } })
    if (!response.ok) throw new Error('重新处理失败，请等待当前任务结束')
    const task = await response.json(); taskStatus.value = `文档任务 ${task.taskId} 已重新创建`
    await loadDocuments()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '重新处理失败' }
  finally { documentAction.value = null }
}
async function deleteDocument(documentId: number) {
  if (!window.confirm('删除后文档将从知识库隐藏，确定继续吗？')) return
  documentAction.value = documentId; error.value = ''
  try {
    const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/documents/${documentId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${props.token}` } })
    if (!response.ok) throw new Error('文档删除失败，请确认编辑权限')
    await loadDocuments(); taskStatus.value = '文档已隐藏，向量清理将在后台完成'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '文档删除失败' }
  finally { documentAction.value = null }
}

function selectFile(event: Event) { selectedFile.value = (event.target as HTMLInputElement).files?.[0] }
async function upload() {
  if (!selectedFile.value) return
  taskStatus.value='上传中…'; error.value=''
  const data=new FormData(); data.append('file',selectedFile.value)
  try {
    const response=await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/documents`,{method:'POST',headers:{Authorization:`Bearer ${props.token}`,'Idempotency-Key':crypto.randomUUID()},body:data})
    if(!response.ok) throw new Error('上传失败，请确认知识库权限')
    const task=await response.json(); taskStatus.value=`任务 ${task.taskId} 已创建，处理中…`
    for(let attempt=0;attempt<120;attempt++) { await new Promise(resolve=>setTimeout(resolve,1500)); const state=await fetch(`/api/document-tasks/${task.taskId}`,{headers:{Authorization:`Bearer ${props.token}`}}); if(!state.ok) throw new Error('任务状态读取失败'); const value=await state.json(); if(value.status==='SUCCEEDED'){taskStatus.value='文档处理完成，可以检索';return} if(value.status==='FAILED') throw new Error(`文档处理失败：${value.errorCode ?? 'UNKNOWN'}`) }
    throw new Error('任务等待超时')
  } catch(cause) { error.value=cause instanceof Error?cause.message:'上传失败'; taskStatus.value='' }
}

async function ask() {
  answering.value=true; answerText.value=''; citations.value=[]; error.value=''; answerAbortController = new AbortController()
  try {
    const response=await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/answers/stream`,{method:'POST',headers:{Authorization:`Bearer ${props.token}`,'Content-Type':'application/json'},body:JSON.stringify({question:answerQuestion.value,topK:4,mode:'HYBRID',rerank:false}),signal:answerAbortController.signal})
    if(!response.ok || !response.body) throw new Error('问答请求失败')
    const reader=response.body.getReader(); const decoder=new TextDecoder(); let buffer=''
    while(true){const {value,done}=await reader.read(); if(done) break; buffer+=decoder.decode(value,{stream:true}); const events=buffer.split('\n\n'); buffer=events.pop() ?? ''; for(const block of events){const name=block.match(/^event:\s*(.+)$/m)?.[1]; const raw=block.match(/^data:\s*(.+)$/m)?.[1]; if(!name||!raw) continue; const data=JSON.parse(raw); if(name==='delta') answerText.value+=data.text ?? ''; if(name==='citation') citations.value=[...citations.value,data]; if(name==='error') throw new Error('流式问答失败');}}
  } catch(cause) { if (!(cause instanceof DOMException && cause.name === 'AbortError')) error.value=cause instanceof Error?cause.message:'问答失败' }
  finally { answering.value=false; answerAbortController = undefined }
}
function stopAnswer() { answerAbortController?.abort() }

onMounted(() => loadKnowledgeBases().catch(cause => { error.value = cause instanceof Error ? cause.message : '知识库列表读取失败' }))
</script>

<template>
  <section class="workspace">
    <header class="topbar"><div><p class="eyebrow">KNOWFLOW AI / SEARCH</p><h2>企业知识检索</h2></div><button class="ghost" @click="emit('logout')">退出登录</button></header>
    <section class="base-panel"><div class="panel-title"><h3>我的知识库</h3><button class="link-button" @click="loadKnowledgeBases">刷新</button></div><div class="base-actions"><select v-model="form.knowledgeBaseId" required><option disabled value="">选择知识库</option><option v-for="base in knowledgeBases" :key="base.id" :value="String(base.id)">{{ base.name }} · {{ base.role }}</option></select><input v-model.trim="newKnowledgeBaseName" placeholder="新知识库名称" maxlength="128" /><button :disabled="creatingKnowledgeBase || !newKnowledgeBaseName.trim()" @click="createKnowledgeBase">{{ creatingKnowledgeBase ? '创建中…' : '创建知识库' }}</button></div></section>
    <form class="search-card" @submit.prevent="search"><label class="query-field">输入问题<input v-model.trim="form.query" required placeholder="例如：如何申请知识库访问权限？" /></label><label>检索模式<select v-model="form.mode"><option value="VECTOR">Vector</option><option value="BM25">BM25</option><option value="HYBRID">Hybrid · RRF</option></select></label><label class="check-label"><input v-model="form.rerank" type="checkbox" />启用重排</label><button :disabled="busy || !form.knowledgeBaseId">{{ busy ? '检索中…' : '开始检索' }}</button></form>
    <section class="tool-row"><label class="upload-label">上传文档<input type="file" accept=".pdf,.md,.markdown,.docx,.txt,text/plain,application/pdf" @change="selectFile" /></label><button class="secondary" :disabled="!selectedFile || !form.knowledgeBaseId" @click="upload">上传并处理</button><button class="secondary" :disabled="!form.knowledgeBaseId" @click="loadDocuments">刷新文档</button><span v-if="taskStatus" class="muted">{{ taskStatus }}</span></section>
    <section v-if="documents.length" class="document-panel"><div class="panel-title"><h3>文档治理</h3><small>{{ documents.length }} 个文档</small></div><div v-for="document in documents" :key="document.id" class="document-row"><span class="status-dot" :class="document.status.toLowerCase()"></span><strong>{{ document.name }}</strong><small>v{{ document.indexVersion }} · {{ document.latestTaskStatus }}<template v-if="document.latestTaskStage">/{{ document.latestTaskStage }}</template></small><small v-if="document.errorCode" class="error">{{ document.errorCode }}</small><button class="row-button" :disabled="documentAction !== null" @click="reindex(document.id)">重处理</button><button class="remove-button" :disabled="documentAction !== null" @click="deleteDocument(document.id)">删除</button></div></section>
    <section class="member-panel"><div class="panel-title"><h3>成员权限</h3><button class="link-button" @click="loadMembers">刷新</button></div><form class="member-form" @submit.prevent="saveMember"><input v-model.trim="memberForm.userId" required inputmode="numeric" placeholder="用户 ID" /><select v-model="memberForm.role"><option value="VIEWER">VIEWER · 只读</option><option value="EDITOR">EDITOR · 可编辑</option></select><button :disabled="!form.knowledgeBaseId">授权</button></form><div v-for="member in members" :key="member.userId" class="document-row"><strong>{{ member.username }}</strong><small>{{ member.role }} · {{ member.status }}</small><button v-if="member.role !== 'OWNER'" class="remove-button" @click="removeMember(member.userId)">移除</button></div><p v-if="!members.length" class="muted">点击刷新查看知识库成员</p></section>
    <p v-if="error" class="error notice">{{ error }}</p><p v-else-if="!hits.length" class="empty">输入问题后，可信片段会显示在这里。</p>
    <div v-else class="results"><article v-for="(hit,index) in hits" :key="`${hit.documentName}-${hit.paragraphNumber}-${index}`" class="result"><div class="result-meta"><span>C{{ index + 1 }}</span><strong>{{ hit.documentName }}</strong><small v-if="hit.pageNumber">第 {{ hit.pageNumber }} 页</small><small v-else-if="hit.paragraphNumber">段落 {{ hit.paragraphNumber }}</small><small class="score">{{ hit.score.toFixed(3) }}</small></div><p>{{ hit.content }}</p></article></div>
    <section class="answer-card"><div class="answer-heading"><div><p class="eyebrow">EVIDENCE ANSWER</p><h3>基于证据问答</h3></div><span v-if="answering" class="muted">正在生成…</span></div><form class="ask-row" @submit.prevent="ask"><input v-model.trim="answerQuestion" required :disabled="answering" placeholder="针对当前知识库继续提问" /><button v-if="answering" type="button" class="secondary" @click="stopAnswer">停止生成</button><button v-else :disabled="!form.knowledgeBaseId">提问</button></form><p v-if="answerText" class="answer-text">{{ answerText }}</p><div v-if="citations.length" class="citation-list"><small v-for="citation in citations" :key="`${citation.documentName}-${citation.paragraphNumber}`">{{ citation.documentName }} · 段落 {{ citation.paragraphNumber ?? '—' }}：{{ citation.quote }}</small></div></section>
    <section class="history-panel"><div class="panel-title"><h3>最近会话</h3><button class="link-button" @click="loadConversations">刷新</button></div><p v-if="!conversations.length" class="muted">暂无历史会话</p><div v-for="conversation in conversations" :key="conversation.id" class="history-row">会话 #{{ conversation.id }}<small>{{ new Date(conversation.createdAt).toLocaleString() }}</small></div></section>
  </section>
</template>
