<script setup lang="ts">
import ModalDialog from './ModalDialog.vue'
import { computed, onMounted, shallowRef } from 'vue'
import { createApi } from '../api'
import { useScope } from '../useScope'
import { roleName } from '../types'
import type { Account, KnowledgeBase } from '../types'
import DocumentsPane from './DocumentsPane.vue'
import AssistantPane from './AssistantPane.vue'
import MembersPane from './MembersPane.vue'

const props = defineProps<{ token: string }>()
const emit = defineEmits<{ logout: [] }>()
const api = createApi(props.token, () => emit('logout'))
const { signal, error, busy, run } = useScope()
const bases = shallowRef<KnowledgeBase[]>([])
const user = shallowRef<Account>()
const selectedId = shallowRef<number>()
const tab = shallowRef<'documents' | 'assistant' | 'settings'>('documents')
const more = shallowRef(false)
const creating = shallowRef(false)
const newName = shallowRef('')
const menuOpen = shallowRef(false)
let baseCursor = 0
const selected = computed(() => bases.value.find(base => base.id === selectedId.value))
function select(base: KnowledgeBase) { selectedId.value = base.id; tab.value = 'documents'; menuOpen.value = false }
async function loadBases(append = false) {
  const after = append ? baseCursor : 0
  const page = await api.json<KnowledgeBase[]>(`/knowledge-bases?limit=100&afterId=${after}`, { signal })
  signal.throwIfAborted()
  baseCursor = page.at(-1)?.id ?? baseCursor
  bases.value = append ? [...bases.value, ...page.filter(item => !bases.value.some(base => base.id === item.id))] : page; more.value = page.length === 100
  if (!selected.value) selectedId.value = bases.value[0]?.id
}
function create() {
  void run(async () => {
    const base = await api.json<KnowledgeBase>('/knowledge-bases', { method: 'POST', body: JSON.stringify({ name: newName.value.trim() }), signal })
    signal.throwIfAborted(); bases.value = [...bases.value, base]; select(base); creating.value = false; newName.value = ''
  })
}
function renamed(base: KnowledgeBase) { bases.value = bases.value.map(item => item.id === base.id ? base : item) }
onMounted(() => run(async () => { user.value = await api.json<Account>('/auth/me', { signal }); await loadBases() }))
</script>

<template>
  <div class="workspace-shell">
    <aside class="sidebar" :class="{ open: menuOpen }">
      <a class="brand" href="#" @click.prevent="tab = 'documents'"><span class="brand-mark">K</span>KnowFlow<span class="brand-ai">AI</span></a>
      <div class="sidebar-heading"><span>我的知识库</span><button class="icon-button" aria-label="创建知识库" @click="creating = true">＋</button></div>
      <nav class="base-nav" aria-label="知识库">
        <button v-for="base in bases" :key="base.id" :class="{ selected: selectedId === base.id }" @click="select(base)"><span class="book-symbol" aria-hidden="true">▤</span><span class="truncate">{{ base.name }}</span><span v-if="base.role === 'VIEWER'" class="tiny">只读</span></button>
        <p v-if="!bases.length && !busy" class="sidebar-empty">还没有知识库，创建一个开始整理资料。</p>
        <button v-if="more" :disabled="busy" @click="run(() => loadBases(true))">加载更多</button>
      </nav>
      <button class="sidebar-create" @click="creating = true">＋ 新建知识库</button>
      <div class="sidebar-footer"><div class="avatar">{{ user?.username.slice(0, 1).toUpperCase() ?? '·' }}</div><div class="account-name truncate">{{ user?.username ?? '正在加载' }}<small>用户 ID {{ user?.id ?? '—' }}</small></div><button class="icon-button" aria-label="退出登录" title="退出登录" @click="emit('logout')">↪</button></div>
    </aside>
    <main class="main-workspace">
      <header class="workspace-header"><button class="icon-button mobile-menu" aria-label="切换知识库导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">☰</button><span class="breadcrumb">知识库 <span>/</span> <strong>{{ selected?.name ?? '开始使用' }}</strong></span><span v-if="selected" class="permission-label">{{ roleName(selected.role) }}</span></header>
      <p v-if="error" class="error-banner" role="alert">{{ error }} <button class="text-button" :disabled="busy" @click="run(() => loadBases())">重试</button></p>
      <template v-if="selected">
        <div class="page-heading"><div><p class="section-label">团队知识空间</p><h1>{{ selected.name }}</h1></div><button class="primary" @click="tab = 'assistant'">向知识库提问 <span aria-hidden="true">↗</span></button></div>
        <nav class="tabs" aria-label="知识库功能"><button :class="{ active: tab === 'documents' }" @click="tab = 'documents'">文档</button><button :class="{ active: tab === 'assistant' }" @click="tab = 'assistant'">问答与搜索</button><button :class="{ active: tab === 'settings' }" @click="tab = 'settings'">成员与设置</button></nav>
        <DocumentsPane v-if="tab === 'documents'" :key="`${selected.id}-documents`" :api="api" :base="selected" @ask="tab = 'assistant'" />
        <AssistantPane v-else-if="tab === 'assistant'" :key="`${selected.id}-assistant`" :api="api" :base="selected" />
        <MembersPane v-else :key="`${selected.id}-settings`" :api="api" :base="selected" @renamed="renamed" />
      </template>
      <div v-else class="welcome empty-state"><span class="empty-symbol" aria-hidden="true">▤</span><h1>{{ busy ? '正在加载知识库…' : '为团队的知识留一个位置' }}</h1><p>把文档放在一起，让每一个问题都有出处。</p><button v-if="!busy" class="primary" @click="creating = true">创建第一个知识库</button></div>
    </main>
    <ModalDialog v-if="creating" title="创建知识库" :busy="busy" @close="creating = false"><h2 id="create-title">创建知识库</h2><p class="muted">按团队、项目或主题组织你的资料。</p><form @submit.prevent="create"><label>知识库名称<input v-model="newName" autofocus required maxlength="128" placeholder="例如：产品研发手册" /></label><p v-if="error" class="error" role="alert">{{ error }}</p><div class="modal-actions"><button type="button" :disabled="busy" @click="creating = false">取消</button><button class="primary" :disabled="busy || !newName.trim()">{{ busy ? '创建中…' : '创建' }}</button></div></form></ModalDialog>
  </div>
</template>
