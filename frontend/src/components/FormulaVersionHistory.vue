<script setup>
defineProps({
  versions: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  hasMore: { type: Boolean, default: false },
});
defineEmits(['load-more']);
</script>

<template>
  <section class="formula-history" aria-label="公式版本历史">
    <h4>公式版本历史</h4>
    <p v-if="loading">正在读取历史…</p>
    <p v-else-if="error" class="field-error">{{ error }}</p>
    <ol v-else><li v-for="version in versions" :key="version.id">版本 {{ version.version }} · {{ version.createdAt }}</li></ol>
    <button v-if="hasMore" type="button" class="secondary-button" :disabled="loading" @click="$emit('load-more')">加载更早版本</button>
  </section>
</template>

