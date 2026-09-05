<script setup lang="ts">
import { onMounted, shallowRef } from 'vue'
import type { Api } from '../api'
import type { Department } from '../types'
import { loadDepartments } from '../departments'
import { useScope } from '../useScope'
const props = defineProps<{ api: Api; admin: boolean }>()
const emit = defineEmits<{ changed: [] }>()
const { signal, error, busy, run } = useScope()
const departments = shallowRef<Department[]>([]), name = shallowRef(''), notice = shallowRef('')
async function load() { departments.value = await loadDepartments(props.api, signal) }
function membership(department: Department) {
  void run(async () => {
    await props.api.json(`/departments/${department.id}/membership`, { method: department.joined ? 'DELETE' : 'PUT', signal })
    signal.throwIfAborted()
    emit('changed')
    notice.value = department.joined ? `已退出 ${department.name}` : `已加入 ${department.name}`
    await load()
  })
}
function create() {
  void run(async () => {
    await props.api.json('/departments', { method: 'POST', body: JSON.stringify({ name: name.value.trim() }), signal })
    await load(); name.value = ''; notice.value = '用户组已创建'
  })
}
onMounted(() => run(load))
</script>

<template>
  <section class="pane settings-pane">
    <div class="toolbar"><h1>用户组</h1><button :disabled="busy" @click="run(load)">刷新</button></div>
    <p class="muted">可以同时加入多个用户组，加入后即可访问对该组开放的知识库。退出后，仅撤销通过该组获得的访问权限。</p>
    <p v-if="error" class="error-banner" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <form v-if="admin" class="member-form" @submit.prevent="create"><label>新用户组名称<input v-model="name" required maxlength="128" placeholder="例如：产品研发组" /></label><button :disabled="busy || !name.trim()">创建用户组</button></form>
    <div class="table-scroll"><table><thead><tr><th>用户组</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="department in departments" :key="department.id"><td>{{ department.name }}</td><td>{{ department.joined ? '已加入' : '未加入' }}</td><td><button :disabled="busy" @click="membership(department)">{{ department.joined ? '退出组' : '加入组' }}</button></td></tr></tbody></table></div>
    <p v-if="!departments.length && !busy" class="muted">暂无用户组，管理员可在这里创建。</p>
  </section>
</template>
