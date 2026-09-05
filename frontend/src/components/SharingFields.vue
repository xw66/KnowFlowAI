<script setup lang="ts">
import type { Department, Sharing } from '../types'
defineProps<{ departments: Department[]; disabled?: boolean }>()
const model = defineModel<Sharing>({ required: true })
function changeVisibility(event: Event) {
  model.value = { visibility: (event.target as HTMLSelectElement).value as Sharing['visibility'], departmentIds: [] }
}
function toggle(id: number, event: Event) {
  model.value = { ...model.value, departmentIds: (event.target as HTMLInputElement).checked
    ? [...model.value.departmentIds, id] : model.value.departmentIds.filter(value => value !== id) }
}
</script>

<template>
  <fieldset :disabled="disabled" class="sharing-fields">
    <legend>知识库开放范围</legend>
    <label>可见范围<select :value="model.visibility" @change="changeVisibility"><option value="PRIVATE">仅指定成员</option><option value="ALL">全员只读（所有已登录用户）</option><option value="DEPARTMENTS">指定用户组只读</option></select></label>
    <div v-if="model.visibility === 'DEPARTMENTS'" class="department-options">
      <label v-for="department in departments" :key="department.id" class="department-option"><input type="checkbox" :checked="model.departmentIds.includes(department.id)" @change="toggle(department.id, $event)" />{{ department.name }}</label>
      <p v-if="!departments.length" class="muted">尚无用户组，请联系管理员创建。</p>
      <p v-if="!model.departmentIds.length" class="muted">请至少选择一个用户组。</p>
    </div>
    <p class="muted small">命中任一用户组即可查看文档和问答。用户可自助加入组；编辑权限需单独授予。已有成员授权始终保留。</p>
  </fieldset>
</template>

<style scoped>
.sharing-fields { border: 0; padding: 0; margin: 20px 0; min-width: 0; }
.sharing-fields legend { font-weight: 600; margin-bottom: 12px; }
.department-options { max-height: 220px; overflow: auto; margin-top: 12px; }
.department-option { display: flex; align-items: center; gap: 8px; padding: 6px 0; }
.department-option input { width: auto; margin: 0; }
</style>
