<script setup lang="ts">
import { reactive, shallowRef } from 'vue'
import { createApi } from '../api'
import { useScope } from '../useScope'
const emit = defineEmits<{ authenticated: [token: string] }>()
const form = reactive({ username: '', password: '' })
const mode = shallowRef<'login' | 'register'>('login')
const { signal, error, busy, run } = useScope()
const api = createApi('', () => {})
function submit() {
  void run(async () => {
    const body = JSON.stringify(form)
    if (mode.value === 'register') {
      await api.json('/auth/register', { method: 'POST', body, signal })
      mode.value = 'login'
    }
    const result = await api.json<{ accessToken: string }>('/auth/login', { method: 'POST', body, signal })
    signal.throwIfAborted(); emit('authenticated', result.accessToken)
  })
}
</script>
<template>
  <main class="login-page"><a href="#" class="brand"><span class="brand-mark">K</span>KnowFlow<span class="brand-ai">AI</span></a><section class="login-box"><span class="login-symbol" aria-hidden="true">▤</span><h1>{{ mode === 'login' ? '回到你的知识空间' : '创建 KnowFlow 账号' }}</h1><p class="muted">整理文档，查找知识，让回答有据可依。</p><form @submit.prevent="submit"><label>用户名<input v-model.trim="form.username" autocomplete="username" required pattern="[A-Za-z0-9_]{3,64}" maxlength="64" placeholder="3–64 位字母、数字或下划线" /></label><label>密码<input v-model="form.password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" type="password" required minlength="8" maxlength="72" placeholder="至少 8 个字符" /></label><p v-if="error" class="error" role="alert">{{ error }}</p><button class="primary wide" :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '登录' : '注册并登录' }}</button></form><p class="login-switch">{{ mode === 'login' ? '还没有账号？' : '已有账号？' }} <button class="text-button" :disabled="busy" @click="mode = mode === 'login' ? 'register' : 'login'; error = ''">{{ mode === 'login' ? '创建账号' : '登录' }}</button></p></section><p class="login-footer">KnowFlow AI · 企业知识治理与智能检索</p></main>
</template>
