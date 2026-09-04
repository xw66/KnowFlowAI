<script setup lang="ts">
import ModalDialog from './ModalDialog.vue'
import { computed, onMounted, onUnmounted, shallowRef } from 'vue'
import type { Api } from '../api'
import { errorText } from '../api'
import type { DocumentItem, KnowledgeBase } from '../types'
import { dateLabel } from '../types'
import { useScope } from '../useScope'

const props = defineProps<{ api: Api; base: KnowledgeBase }>()
defineEmits<{ ask: [] }>()
const { signal, error, busy, run } = useScope()
const documents = shallowRef<DocumentItem[]>([]), filter = shallowRef(''), more = shallowRef(false), loading = shallowRef(true)
const pendingDelete = shallowRef<DocumentItem>()
const queued = shallowRef<{ file: File; key: string }>()
const notice = shallowRef('')
const detail = shallowRef<DocumentItem>()
const canEdit = computed(() => props.base.role !== 'VIEWER')
const visible = computed(() => documents.value.filter(doc => doc.name.toLowerCase().includes(filter.value.toLowerCase())))
const path = `/knowledge-bases/${props.base.id}/documents`
let timer: ReturnType<typeof setTimeout> | undefined
let refreshVersion = 0
const reindexKeys = new Map<number, string>()
const active = (doc: DocumentItem) => !['SUCCEEDED', 'FAILED'].includes(doc.latestTaskStatus)
const stateLabel = (doc: DocumentItem) => active(doc) ? '处理中' : doc.latestTaskStatus === 'FAILED' ? '处理失败' : '可检索'
const sizeLabel = (size: number) => size < 1048576 ? `${Math.max(1, Math.round(size / 1024))} KB` : `${(size / 1048576).toFixed(1)} MB`
async function load(append = false) {
  const version = ++refreshVersion
  clearTimeout(timer)
  const after = append ? documents.value.at(-1)?.id ?? 0 : 0
  const page = await props.api.json<DocumentItem[]>(`${path}?limit=50&afterId=${after}`, { signal })
  if (signal.aborted || version !== refreshVersion) return
  documents.value = append ? [...documents.value, ...page] : page; more.value = page.length === 50; loading.value = false
  if (documents.value.some(active)) schedule()
}
function schedule() {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    const version = refreshVersion
    try {
      const updates = await Promise.all(documents.value.filter(active).map(doc => props.api.json<DocumentItem>(`${path}/${doc.id}`, { signal })))
      signal.throwIfAborted()
      if (version !== refreshVersion) return
      documents.value = documents.value.map(doc => updates.find(item => item.id === doc.id) ?? doc)
      if (documents.value.some(active)) schedule()
    } catch (cause) { if (!signal.aborted) error.value = errorText(cause) }
  }, 2500)
}
function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]; input.value = ''
  if (!file) return
  queued.value = { file, key: crypto.randomUUID() }
  upload()
}
function upload() {
  const item = queued.value
  if (!item) return
  void run(async () => {
    const data = new FormData(); data.append('file', item.file)
    await props.api.json(path, { method: 'POST', body: data, headers: { 'Idempotency-Key': item.key }, signal })
    signal.throwIfAborted(); queued.value = undefined; notice.value = '文档已上传，处理状态会自动更新。你可以继续使用知识库。'; await load()
  })
}
function reindex(doc: DocumentItem) {
  void run(async () => {
    const key = reindexKeys.get(doc.id) ?? crypto.randomUUID()
    reindexKeys.set(doc.id, key)
    await props.api.json(`${path}/${doc.id}/reindex`, { method: 'POST', headers: { 'Idempotency-Key': key }, signal })
    reindexKeys.delete(doc.id)
    notice.value = '已开始重新处理，原有可用版本会保留到处理成功。'; await load()
  })
}
function remove() {
  const doc = pendingDelete.value
  if (!doc) return
  void run(async () => {
    await props.api.json(`${path}/${doc.id}`, { method: 'DELETE', signal })
    signal.throwIfAborted(); pendingDelete.value = undefined; detail.value = undefined; notice.value = '文档已删除'; await load()
  })
}
onMounted(() => run(async () => { try { await load() } finally { loading.value = false } }))
onUnmounted(() => clearTimeout(timer))
</script>
<template>
  <section class="pane documents-pane"><div class="toolbar"><div><h2>全部文档 <span class="count">{{ documents.length }}{{ more ? '+' : '' }}</span></h2><p class="muted">知识库中的原始资料与处理状态</p></div><div class="toolbar-actions"><button :disabled="busy" @click="run(() => load())">刷新</button><label v-if="canEdit" class="button primary upload-button">＋ 上传文档<input aria-label="上传文档" type="file" accept=".pdf,.md,.markdown,.txt,.docx" :disabled="busy" @change="selectFile" /></label></div></div>
    <p v-if="error" class="error-banner" role="alert">{{ error }} <button v-if="queued" class="text-button" :disabled="busy" @click="upload">重试上传</button></p><p v-if="notice" class="notice" role="status">{{ notice }}</p><p v-if="busy && queued" class="muted" role="status">正在上传 {{ queued.file.name }}…</p>
    <div v-if="documents.length" class="list-filter"><input v-model="filter" aria-label="按文档名称筛选" placeholder="搜索文档名称…" /><span class="muted small">支持 PDF、Word、Markdown、TXT</span></div>
    <div v-if="loading" class="empty-state" role="status">正在加载文档…</div>
    <div v-else-if="!documents.length" class="empty-state"><span class="empty-symbol" aria-hidden="true">▤</span><h3>知识从第一份文档开始</h3><p>{{ canEdit ? '上传团队手册、项目资料或工作笔记，即可基于原文提问。' : '这里还没有文档，请联系知识库所有者添加资料。' }}</p><label v-if="canEdit" class="button primary upload-button">上传第一份文档<input aria-label="上传第一份文档" type="file" accept=".pdf,.md,.markdown,.txt,.docx" :disabled="busy" @change="selectFile" /></label><small>支持 PDF、DOCX、Markdown 和 TXT</small></div>
    <div v-else class="table-scroll"><table><thead><tr><th>名称</th><th>状态</th><th>更新时间</th><th><span class="sr-only">操作</span></th></tr></thead><tbody><tr v-for="doc in visible" :key="doc.id"><td><button class="document-link" @click="detail = doc"><span class="file-icon">{{ doc.name.split('.').at(-1)?.toUpperCase().slice(0,4) }}</span><span>{{ doc.name }}<small>{{ sizeLabel(doc.sizeBytes) }} <template v-if="doc.activeIndexVersion"> · 可用版本 {{ doc.activeIndexVersion }}</template></small></span></button></td><td><span class="status-pill" :class="active(doc) ? 'processing' : doc.latestTaskStatus === 'FAILED' ? 'failed' : 'ready'">{{ stateLabel(doc) }}</span></td><td class="muted small">{{ dateLabel(doc.updatedAt) }}</td><td><div v-if="canEdit" class="row-actions"><button class="text-button" :disabled="busy || active(doc)" @click="reindex(doc)">重新处理</button><button class="text-button danger" :disabled="busy" @click="pendingDelete = doc">删除</button></div></td></tr></tbody></table><p v-if="!visible.length" class="empty-state">没有匹配的文档名称</p><button v-if="more" class="load-more" :disabled="busy" @click="run(() => load(true))">加载更多文档</button></div>
    <div v-if="detail" class="detail-strip"><button class="icon-button" aria-label="关闭文档详情" @click="detail = undefined">×</button><h3>{{ detail.name }}</h3><p>处理状态：{{ stateLabel(detail) }}。<span v-if="detail.errorCode">错误：{{ detail.errorCode }}。</span>当前索引版本 {{ detail.indexVersion }}。</p><p class="muted">要查找原文片段和引用，请在问答与搜索中检索此知识库。</p><button @click="$emit('ask')">查找文档内容</button></div>
    <ModalDialog v-if="pendingDelete" title="删除文档" :busy="busy" @close="pendingDelete = undefined"><h2 id="delete-title">删除文档？</h2><p>「{{ pendingDelete.name }}」将无法继续检索，关联的历史回答也会重新检查来源。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="modal-actions"><button :disabled="busy" @click="pendingDelete = undefined">取消</button><button class="danger-button" :disabled="busy" @click="remove">确认删除</button></div></ModalDialog>
  </section>
</template>
