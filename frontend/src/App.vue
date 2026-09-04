<script setup lang="ts">
import { shallowRef } from 'vue'
import LoginPanel from './components/LoginPanel.vue'
import Workspace from './components/Workspace.vue'

const token = shallowRef(localStorage.getItem('knowflow.token') ?? '')
function authenticated(value: string) { token.value = value; localStorage.setItem('knowflow.token', value) }
function logout() { token.value = ''; localStorage.removeItem('knowflow.token') }
</script>

<template><LoginPanel v-if="!token" @authenticated="authenticated" /><Workspace v-else :key="token" :token="token" @logout="logout" /></template>
