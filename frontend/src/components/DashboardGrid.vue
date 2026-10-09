<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { GridStack } from 'gridstack/dist/vue';
import 'gridstack/dist/gridstack.css';
import MetricCard from './MetricCard.vue';

const props = defineProps({
  items: { type: Array, default: () => [] },
  cards: { type: Array, default: () => [] },
  editing: { type: Boolean, default: false },
  periodLabel: { type: String, default: '' },
});
const emit = defineEmits(['layout-change', 'adjust']);

const gridRef = ref(null);
let syncing = false;
let mediaQuery = null;
const cardsByWidget = computed(() => new Map(props.cards.map((card) => [card.widgetId, card])));
const gridOptions = computed(() => ({
  column: 12,
  cellHeight: 108,
  margin: 10,
  float: true,
  // Vue's GridStack wrapper teleports card content after grid initialization;
  // measure explicitly after that render instead of sizing an empty placeholder.
  sizeToContent: false,
  animate: !(mediaQuery?.matches),
  disableOneColumnMode: true,
  handle: '.gridstack-drag-handle',
  disableDrag: !props.editing,
  disableResize: !props.editing,
  children: props.items.map((item) => ({
    ...gridItem(item),
    component: 'MetricCard',
    props: {
      card: cardsByWidget.value.get(item.widgetId),
      item,
      editing: props.editing,
      periodLabel: props.periodLabel,
      onAdjust: (change) => emit('adjust', change),
    },
  })),
}));

function gridItem(item) {
  return {
    id: item.widgetId,
    x: item.x,
    y: item.y,
    w: item.w,
    h: item.h,
    minW: item.minW ?? 3,
    minH: item.minH ?? 2,
    maxW: item.maxW ?? 12,
    maxH: item.maxH ?? 6,
  };
}

function currentGrid() { return gridRef.value?.getGrid?.() || null; }

function setEditingState() {
  const grid = currentGrid();
  if (!grid) return;
  if (props.editing) grid.enable(false);
  else grid.disable(false);
}

function applyItems(items) {
  const grid = currentGrid();
  if (!grid) return;
  syncing = true;
  grid.batchUpdate();
  const elements = grid.getGridItems?.() || [];
  for (const item of items) {
    const element = elements.find((entry) => entry.gridstackNode?.id === item.widgetId || entry.getAttribute('gs-id') === item.widgetId);
    if (element) grid.update(element, {
      ...gridItem(item), component: 'MetricCard', props: {
        card: cardsByWidget.value.get(item.widgetId), item, editing: props.editing,
        periodLabel: props.periodLabel, onAdjust: (change) => emit('adjust', change),
      },
    });
  }
  grid.batchUpdate(false);
  void nextTick().then(() => new Promise((resolve) => requestAnimationFrame(resolve))).then(() => {
    if (!props.editing) {
      for (const element of grid.getGridItems?.() || []) {
        const content = element.querySelector('.grid-stack-item-content')?.firstElementChild;
        if (content) grid.resizeToContent?.(element);
      }
    }
    syncing = false;
  });
}

function normalizeNodes(nodes) {
  const byId = new Map(props.items.map((item) => [item.widgetId, item]));
  return (nodes || []).map((node) => {
    const original = byId.get(node.id);
    return {
      widgetId: node.id,
      x: node.x,
      y: node.y,
      w: node.w,
      h: node.h,
      minW: original?.minW ?? 3,
      minH: original?.minH ?? 2,
      maxW: original?.maxW ?? 12,
      maxH: original?.maxH ?? 6,
    };
  });
}

function layoutChanged(_event, nodes) {
  if (!props.editing || syncing) return;
  const changed = normalizeNodes(nodes);
  if (changed.length) emit('layout-change', changed);
}

watch(() => props.editing, () => void nextTick(setEditingState));
watch([() => props.items, () => props.cards, () => props.editing], () => void nextTick(() => applyItems(props.items)), { deep: true });

if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
}
onBeforeUnmount(() => { syncing = true; });
</script>

<template>
  <GridStack ref="gridRef" class="dashboard-grid" :class="{ 'is-editing': editing }" :options="gridOptions"
    :components="{ MetricCard }" @change="layoutChanged" />
</template>
