<script setup>
import { computed } from 'vue';
import ReferencePicker from './ReferencePicker.vue';
import { FUNCTION_HINTS, cloneNode, defaultNode, nodeStats } from './formulaHelpers.js';

const props = defineProps({
  node: { type: Object, required: true },
  path: { type: String, default: 'formula.ast.root' },
  depth: { type: Number, default: 1 },
  metrics: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
  accounts: { type: Array, default: () => [] },
  candidateMetricId: { type: String, default: '' },
  fieldErrors: { type: Object, default: () => ({}) },
});
const emit = defineEmits(['update:node']);
const stats = computed(() => nodeStats(props.node));
const hint = computed(() => FUNCTION_HINTS.find((item) => item.code === props.node.kind));
const fieldError = computed(() => {
  const value = props.fieldErrors?.[props.path];
  if (!value) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'object' && value.message) return String(value.message);
  return String(value);
});
const canNest = computed(() => props.depth < 32 && stats.value.count < 256);
function replace(value) { emit('update:node', cloneNode(value)); }
function changeKind(kind) { replace(defaultNode(kind)); }
function updateField(field, value) { replace({ ...props.node, [field]: value }); }
function updateReference(value) { replace({ ...props.node, referenceKind: value.referenceKind, key: value.key }); }
function replaceChild(index, value) { const children = props.node.children.map((child, childIndex) => childIndex === index ? cloneNode(value) : cloneNode(child)); replace({ ...props.node, children }); }
function addChild() { if (!hint.value || !canNest.value || props.node.children.length >= hint.value.max) return; replace({ ...props.node, children: [...props.node.children, defaultNode('CONSTANT')] }); }
function removeChild(index) { if (!hint.value || props.node.children.length <= hint.value.min) return; replace({ ...props.node, children: props.node.children.filter((_, childIndex) => childIndex !== index) }); }
</script>

<template>
  <fieldset class="formula-node" :aria-label="`公式节点 ${path}`" :data-formula-path="path" :class="{ 'formula-node-invalid': fieldError }">
    <legend>{{ path }}</legend>
    <label>节点类型<select :value="node.kind" :aria-invalid="fieldError ? 'true' : 'false'" @change="changeKind($event.target.value)"><option value="CONSTANT">常数</option><option value="REF">引用</option><option v-for="item in FUNCTION_HINTS" :key="item.code" :value="item.code" :title="item.help">{{ item.name }}</option></select></label>
    <p v-if="fieldError" class="field-error formula-node-error" data-formula-error="true" tabindex="-1" role="alert">{{ fieldError }}</p>
    <label v-if="node.kind === 'CONSTANT'">数值<input :value="node.value" inputmode="decimal" @input="updateField('value', $event.target.value)"></label>
    <ReferencePicker v-else-if="node.kind === 'REF'" :model-value="{ referenceKind: node.referenceKind, key: node.key }" :metrics="metrics" :categories="categories" :accounts="accounts" :candidate-metric-id="candidateMetricId" @update:model-value="updateReference" />
    <div v-else class="formula-children">
      <div class="formula-node-toolbar"><span>{{ hint?.name || node.kind }}：{{ node.children.length }} 个参数</span><button v-if="hint && hint.max > hint.min" type="button" class="secondary-button" :disabled="!canNest || node.children.length >= hint.max" @click="addChild">添加参数</button></div>
      <FormulaNodeEditor v-for="(child, index) in node.children" :key="`${path}.${index}`" :node="child" :path="`${path}.children[${index}]`" :depth="depth + 1" :metrics="metrics" :categories="categories" :accounts="accounts" :candidate-metric-id="candidateMetricId" :field-errors="fieldErrors" @update:node="replaceChild(index, $event)" />
      <p v-if="hint && node.children.length >= hint.max" class="formula-hints">已达到该函数的参数上限 {{ hint.max }}。</p>
      <p v-if="!canNest" class="formula-hints">已接近公式复杂度上限（深度 32 或节点 256），不能继续嵌套。</p>
      <template v-if="hint && node.children.length > hint.min"><button v-for="(child, index) in node.children" :key="`remove-${path}-${index}`" type="button" class="secondary-button" @click="removeChild(index)">删除第 {{ index + 1 }} 个参数</button></template>
    </div>
  </fieldset>
</template>
