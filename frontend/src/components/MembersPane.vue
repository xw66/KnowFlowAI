<script setup lang="ts">
import ModalDialog from './ModalDialog.vue'
import { computed, onMounted, reactive, shallowRef } from 'vue'
import type { Api } from '../api'
import type { KnowledgeBase, Member } from '../types'
import { roleName } from '../types'
import { useScope } from '../useScope'
import SharingFields from './SharingFields.vue'
import { loadDepartments } from '../departments'
import type { Department, Sharing } from '../types'
const props = defineProps<{ api: Api; base: KnowledgeBase }>()
const emit = defineEmits<{ renamed: [base: KnowledgeBase] }>()
const { signal, error, busy, run } = useScope()
const name = shallowRef(props.base.name), notice = shallowRef(''), more = shallowRef(false)
const members = shallowRef<Member[]>([]), removing = shallowRef<Member>()
const form = reactive({ userId: '', role: 'VIEWER' })
const sharing = shallowRef<Sharing>({ visibility: 'PRIVATE', departmentIds: [] })
const departments = shallowRef<Department[]>([])
const sharingReady = shallowRef(false)
const manager = computed(() => props.base.role === 'OWNER' || props.base.role === 'ADMIN')
const path = `/knowledge-bases/${props.base.id}`
async function load(append = false) {
  const page = await props.api.json<Member[]>(`${path}/members?limit=100&afterUserId=${append ? members.value.at(-1)?.userId ?? 0 : 0}`, { signal })
  signal.throwIfAborted(); members.value = append ? [...members.value, ...page] : page; more.value = page.length === 100
}
function rename() { void run(async () => { const base = await props.api.json<KnowledgeBase>(path, { method: 'PUT', body: JSON.stringify({ name: name.value.trim() }), signal }); signal.throwIfAborted(); emit('renamed', base); notice.value = '名称已更新' }) }
function save() { void run(async () => { await props.api.json(`${path}/members/${form.userId}`, { method: 'PUT', body: JSON.stringify({ role: form.role }), signal }); await load(); form.userId = ''; notice.value = '成员权限已保存' }) }
function remove() { const member = removing.value; if (!member) return; void run(async () => { await props.api.json(`${path}/members/${member.userId}`, { method: 'DELETE', signal }); signal.throwIfAborted(); removing.value = undefined; await load(); notice.value = '成员已移除' }) }
function saveSharing() { void run(async () => {
  sharing.value = await props.api.json<Sharing>(`${path}/sharing`, { method: 'PUT', body: JSON.stringify(sharing.value), signal })
  notice.value = '开放范围已更新'
}) }
onMounted(() => run(async () => {
  sharing.value = await props.api.json<Sharing>(`${path}/sharing`, { signal })
  departments.value = await loadDepartments(props.api, signal)
  sharingReady.value = true
  if (manager.value) await load()
}))
</script>
<template><section class="pane settings-pane"><h2>知识库设置</h2><p class="muted">你的权限：{{ roleName(base.role) }}</p><p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p><form class="settings-section" @submit.prevent="rename"><label>知识库名称<input v-model="name" required maxlength="128" :disabled="base.role === 'VIEWER'" /></label><button v-if="base.role !== 'VIEWER'" :disabled="busy || !name.trim() || name.trim() === base.name">保存名称</button></form>
  <form v-if="sharingReady" class="settings-section" @submit.prevent="saveSharing"><SharingFields v-model="sharing" :departments="departments" :disabled="!manager || busy" /><button v-if="manager" :disabled="busy || (sharing.visibility === 'DEPARTMENTS' && !sharing.departmentIds.length)">保存开放范围</button></form><section class="settings-section"><div class="toolbar"><div><h2>成员与权限</h2><p class="muted">系统管理员和所有者可以邀请成员或调整权限。</p></div><button v-if="manager" :disabled="busy" @click="run(() => load())">刷新</button></div><template v-if="manager"><form class="member-form" @submit.prevent="save"><label>用户 ID<input v-model="form.userId" type="number" min="1" step="1" required placeholder="对方可在侧栏底部查看" /></label><label>权限<select v-model="form.role"><option value="VIEWER">只读：查看资料与问答</option><option value="EDITOR">编辑：上传及管理文档</option></select></label><button class="primary" :disabled="busy">添加或更新成员</button></form><div class="table-scroll"><table><thead><tr><th>成员</th><th>权限</th><th>操作</th></tr></thead><tbody><tr v-for="member in members" :key="member.userId"><td>{{ member.username }}<small class="block muted">ID {{ member.userId }}</small></td><td>{{ roleName(member.role) }}<span v-if="member.status !== 'ACTIVE'" class="danger">（已停用）</span></td><td><div v-if="member.role !== 'OWNER'" class="row-actions"><button class="text-button" :disabled="busy" @click="form.userId = String(member.userId); form.role = member.role">编辑</button><button class="text-button danger" :disabled="busy" @click="removing = member">移除</button></div><span v-else class="muted small">知识库所有者</span></td></tr></tbody></table></div><button v-if="more" :disabled="busy" @click="run(() => load(true))">加载更多成员</button></template><p v-else class="permission-note">需要邀请成员或修改权限？请联系知识库所有者或系统管理员。</p></section>
  <ModalDialog v-if="removing" title="移除成员" :busy="busy" @close="removing = undefined"><h2 id="remove-member-title">移除成员？</h2><p>{{ removing.username }} 的单独授权将被移除；全员或部门共享权限仍然有效。</p><p v-if="error" class="error" role="alert">{{ error }}</p><div class="modal-actions"><button :disabled="busy" @click="removing = undefined">取消</button><button class="danger-button" :disabled="busy" @click="remove">确认移除</button></div></ModalDialog>
</section></template>
