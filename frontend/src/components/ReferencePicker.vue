<script setup>
import { computed } from 'vue';
import { REFERENCE_KINDS, TIME_REFERENCES } from './formulaHelpers.js';

const props = defineProps({
  modelValue: { type: Object, default: () => ({ referenceKind: 'METRIC', key: '' }) },
  metrics: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
  accounts: { type: Array, default: () => [] },
  candidateMetricId: { type: String, default: '' },
});
const emit = defineEmits(['update:modelValue']);
const groups = computed(() => [
  { ...REFERENCE_KINDS[0], items: props.metrics.filter((item) => item.status === 'ACTIVE' && item.id !== props.candidateMetricId).map((item) => ({ key: item.id, label: item.name })) },
  { ...REFERENCE_KINDS[1], items: props.categories.filter((item) => item.status === 'ACTIVE' && item.canUseForRecords).map((item) => ({ key: item.id, label: item.name })) },
  { ...REFERENCE_KINDS[2], items: props.categories.filter((item) => item.status === 'ACTIVE' && item.canUseForRecords).map((item) => ({ key: item.id, label: item.name })) },
  { ...REFERENCE_KINDS[3], items: props.accounts.filter((item) => item.status === 'ACTIVE').map((item) => ({ key: item.id, label: item.name })) },
  { ...REFERENCE_KINDS[4], items: TIME_REFERENCES },
]);
function updateKind(kind) { emit('update:modelValue', { referenceKind: kind, key: '' }); }
function updateKey(key) { emit('update:modelValue', { referenceKind: props.modelValue.referenceKind, key }); }
</script>

<template>
  <div class="reference-picker">
    <label>引用类型<select :value="modelValue.referenceKind" @change="updateKind($event.target.value)"><option v-for="kind in groups" :key="kind.kind" :value="kind.kind">{{ kind.label }}</option></select></label>
    <label>引用对象<select :value="modelValue.key" @change="updateKey($event.target.value)"><option value="">请选择</option><optgroup v-for="group in groups.filter((item) => item.kind === modelValue.referenceKind)" :key="group.kind" :label="group.label"><option v-for="item in group.items" :key="item.key" :value="item.key">{{ item.label }}</option></optgroup></select></label>
  </div>
</template>

