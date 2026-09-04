<script setup lang="ts">
import { reactive, shallowRef } from 'vue'

const emit = defineEmits<{ authenticated: [token: string] }>()
const form = reactive({ username: '', password: '' })
const mode = shallowRef<'login' | 'register'>('login')
const busy = shallowRef(false)
const error = shallowRef('')

async function submit() {
  busy.value = true; error.value = ''
  try {
    if (mode.value === 'register') {
      const registered = await fetch('/api/auth/register', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(form) })
      if (!registered.ok) throw new Error('注册失败，请检查用户名和密码')
    }
    const response = await fetch('/api/auth/login', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(form) })
    if (!response.ok) throw new Error('登录失败，请检查账号或密码')
    emit('authenticated', (await response.json()).accessToken)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '请求失败' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="auth-card">
    <p class="eyebrow">KNOWFLOW AI</p><h1>让企业知识<br /><em>流动起来</em></h1>
    <p class="muted">统一治理文档，快速找到可信答案。</p>
    <form class="stack" @submit.prevent="submit">
      <label>用户名<input v-model.trim="form.username" required minlength="3" autocomplete="username" /></label>
      <label>密码<input v-model="form.password" required minlength="8" type="password" autocomplete="current-password" /></label>
      <p v-if="error" class="error">{{ error }}</p><button :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '登录 KnowFlow' : '创建账号' }}</button>
    </form>
    <button class="link-button" @click="mode = mode === 'login' ? 'register' : 'login'">{{ mode === 'login' ? '还没有账号？立即注册' : '已有账号？返回登录' }}</button>
  </section>
</template>
