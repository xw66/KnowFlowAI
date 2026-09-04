<script setup lang="ts">
import { onMounted, useTemplateRef } from 'vue'
defineProps<{ title: string; busy: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = useTemplateRef<HTMLDialogElement>('dialog')
onMounted(() => dialog.value?.showModal())
</script>
<template><dialog ref="dialog" class="modal" :aria-label="title" @cancel.prevent="!busy && emit('close')"><slot /></dialog></template>
