<script setup>
import { metricDataStatusLabel } from '../presentationMaps.js';
const props = defineProps({
  card: { type: Object, default: null },
  item: { type: Object, required: true },
  periodLabel: { type: String, default: '' },
  editing: { type: Boolean, default: false },
});

const emit = defineEmits(['adjust']);

function formatValue(value, metric) {
  if (!value || value.value === null || value.value === undefined) return '暂无数据';
  const precision = Number.isInteger(value.precision) ? value.precision : (metric?.precision ?? 2);
  const numeric = Number(value.value);
  if (!Number.isFinite(numeric)) return String(value.value);
  const rendered = numeric.toLocaleString('zh-CN', { minimumFractionDigits: precision, maximumFractionDigits: precision });
  if (value.displayFormat === 'PERCENT') return `${rendered}%`;
  if (value.displayFormat === 'INTEGER') return rendered;
  return value.displayFormat === 'CURRENCY' ? `¥${rendered}` : rendered;
}

function changeLabel(change) {
  if (!change || (change.absolute === null && change.percent === null)) return '暂无环比';
  if (change.percent === null) return `变化 ${change.absolute}`;
  const percent = Number(change.percent);
  return Number.isFinite(percent) ? `环比 ${percent >= 0 ? '+' : ''}${percent.toFixed(1)}%` : '暂无环比';
}

function adjust(property, amount) {
  emit('adjust', { widgetId: props.item.widgetId, property, amount });
}
</script>

<template>
  <article class="metric-card" :data-widget-id="item.widgetId" :data-metric-id="card?.metric?.id || ''" tabindex="0">
    <template v-if="card">
      <div class="metric-card-heading gridstack-drag-handle">
        <div>
          <h3>{{ card.metric?.name || '未命名指标' }}</h3>
          <p>{{ card.metric?.description || '服务端指标' }}</p>
        </div>
        <span v-if="card.presentation?.hidden" class="hidden-badge">已隐藏</span>
      </div>
      <template v-if="card.presentation?.hidden">
        <p class="metric-hidden-copy">此卡片已隐藏数值，仅保留状态。</p>
      </template>
      <template v-else>
        <p class="metric-value">{{ formatValue(card.value, card.metric) }}</p>
        <div class="metric-meta"><span>{{ metricDataStatusLabel(card.value?.dataStatus) }}</span><span>{{ changeLabel(card.change) }}</span></div>
        <p class="metric-period">{{ periodLabel }}</p>
      </template>
    </template>
    <p v-else class="metric-hidden-copy">该卡片暂不可用。</p>

    <div v-if="editing" class="card-keyboard-controls" role="group" :aria-label="`${card?.metric?.name || '指标'}布局控制`">
      <button type="button" @click="adjust('x', -1)" :aria-label="`${card?.metric?.name || '指标'}左移`">←</button>
      <button type="button" @click="adjust('x', 1)" :aria-label="`${card?.metric?.name || '指标'}右移`">→</button>
      <button type="button" @click="adjust('y', -1)" :aria-label="`${card?.metric?.name || '指标'}上移`">↑</button>
      <button type="button" @click="adjust('y', 1)" :aria-label="`${card?.metric?.name || '指标'}下移`">↓</button>
      <button type="button" @click="adjust('w', 1)" aria-label="增加卡片宽度">宽+</button>
      <button type="button" @click="adjust('w', -1)" aria-label="减少卡片宽度">宽−</button>
      <button type="button" @click="adjust('h', 1)" aria-label="增加卡片高度">高+</button>
      <button type="button" @click="adjust('h', -1)" aria-label="减少卡片高度">高−</button>
    </div>
  </article>
</template>
