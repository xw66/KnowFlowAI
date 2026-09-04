<script setup lang="ts">
import { reactive, shallowRef } from 'vue'

interface Hit { documentName: string; paragraphNumber?: number; pageNumber?: number; score: number; content: string }
const props = defineProps<{ token: string }>()
const emit = defineEmits<{ logout: [] }>()
const form = reactive({ knowledgeBaseId: '', query: '', topK: 5 })
const hits = shallowRef<Hit[]>([]); const busy = shallowRef(false); const error = shallowRef('')

async function search() {
  busy.value = true; error.value = ''; hits.value = []
  try {
    const response = await fetch(`/api/knowledge-bases/${encodeURIComponent(form.knowledgeBaseId)}/search`, { method:'POST', headers:{'Content-Type':'application/json', Authorization:`Bearer ${props.token}`}, body:JSON.stringify({query:form.query,topK:form.topK,mode:'HYBRID',rerank:false}) })
    if (response.status === 401) { emit('logout'); return }
    if (!response.ok) throw new Error('检索失败，请确认知识库权限和索引状态')
    hits.value = await response.json()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '请求失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="workspace">
    <header class="topbar"><div><p class="eyebrow">KNOWFLOW AI / SEARCH</p><h2>企业知识检索</h2></div><button class="ghost" @click="emit('logout')">退出登录</button></header>
    <form class="search-card" @submit.prevent="search"><label>知识库 ID<input v-model="form.knowledgeBaseId" required inputmode="numeric" placeholder="例如 1" /></label><label class="query-field">输入问题<input v-model.trim="form.query" required placeholder="例如：如何申请知识库访问权限？" /></label><button :disabled="busy">{{ busy ? '检索中…' : '开始检索' }}</button></form>
    <p v-if="error" class="error notice">{{ error }}</p><p v-else-if="!hits.length" class="empty">输入问题后，可信片段会显示在这里。</p>
    <div v-else class="results"><article v-for="(hit,index) in hits" :key="`${hit.documentName}-${hit.paragraphNumber}-${index}`" class="result"><div class="result-meta"><span>C{{ index + 1 }}</span><strong>{{ hit.documentName }}</strong><small v-if="hit.pageNumber">第 {{ hit.pageNumber }} 页</small><small v-else-if="hit.paragraphNumber">段落 {{ hit.paragraphNumber }}</small><small class="score">{{ hit.score.toFixed(3) }}</small></div><p>{{ hit.content }}</p></article></div>
  </section>
</template>
