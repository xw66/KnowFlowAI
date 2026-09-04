<script setup lang="ts">
import type { Source } from '../types'
defineProps<{ sources: Source[] }>()
</script>
<template><div v-if="sources.length" class="source-list"><p class="source-heading">参考来源 · {{ sources.length }}</p><details v-for="source in sources" :key="`${source.id}-${source.chunkId}`" class="source"><summary><span class="source-index">{{ source.id }}</span><strong>{{ source.documentName }}</strong><span>{{ source.pageNumber ? `第 ${source.pageNumber} 页` : `段落 ${source.paragraphNumber}` }}</span></summary><blockquote>{{ source.quote }}</blockquote></details></div></template>
