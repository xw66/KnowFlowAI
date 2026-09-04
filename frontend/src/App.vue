<script setup lang="ts">
import { shallowRef } from 'vue'
import LoginPanel from './components/LoginPanel.vue'
import SearchPanel from './components/SearchPanel.vue'

const token = shallowRef(localStorage.getItem('knowflow.token') ?? '')
function authenticated(value: string) { token.value = value; localStorage.setItem('knowflow.token', value) }
function logout() { token.value = ''; localStorage.removeItem('knowflow.token') }
</script>

<template><main class="app-shell"><LoginPanel v-if="!token" @authenticated="authenticated" /><SearchPanel v-else :token="token" @logout="logout" /></main></template>
