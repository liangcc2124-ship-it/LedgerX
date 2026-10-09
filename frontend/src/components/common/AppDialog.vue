<script setup>
import { nextTick, onBeforeUnmount, watch, ref } from 'vue';

const props = defineProps({
  open: { type: Boolean, default: false },
  titleId: { type: String, required: true },
  busy: { type: Boolean, default: false },
  initialFocus: { type: String, default: '' },
  closeOnBackdrop: { type: Boolean, default: true },
});

const emit = defineEmits(['close']);
const dialog = ref(null);
let previousActive = null;
let previousOverflow = '';
let previousInert = false;
let previousInertAttribute = null;

const focusableSelector = [
  'button:not([disabled])', 'input:not([disabled])', 'select:not([disabled])',
  'textarea:not([disabled])', 'a[href]', '[tabindex]:not([tabindex="-1"])',
].join(',');

function focusable() {
  return Array.from(dialog.value?.querySelectorAll(focusableSelector) || [])
    .filter((element) => element.offsetParent !== null && !element.closest('[inert]'));
}

function focusInitial() {
  const target = props.initialFocus ? dialog.value?.querySelector(props.initialFocus) : null;
  (target && !target.disabled ? target : focusable()[0] || dialog.value)?.focus();
}

function handleKeydown(event) {
  if (!props.open) return;
  if (event.key === 'Escape') {
    if (!props.busy) { event.preventDefault(); emit('close'); }
    return;
  }
  if (event.key !== 'Tab') return;
  const elements = focusable();
  if (!elements.length) { event.preventDefault(); dialog.value?.focus(); return; }
  const first = elements[0];
  const last = elements[elements.length - 1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}

function openDialog() {
  previousActive = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  previousOverflow = document.body.style.overflow;
  const app = document.getElementById('app');
  previousInert = Boolean(app?.inert);
  previousInertAttribute = app?.getAttribute('inert') ?? null;
  document.body.style.overflow = 'hidden';
  if (app) app.inert = true;
  document.addEventListener('keydown', handleKeydown, true);
  void nextTick(focusInitial);
}

function restoreDialog() {
  document.removeEventListener('keydown', handleKeydown, true);
  document.body.style.overflow = previousOverflow;
  const app = document.getElementById('app');
  if (app) {
    app.inert = previousInert;
    if (previousInertAttribute === null) app.removeAttribute('inert');
    else app.setAttribute('inert', previousInertAttribute);
  }
  const trigger = previousActive;
  previousActive = null;
  void nextTick(() => {
    if (trigger?.isConnected) {
      trigger.focus();
      return;
    }
    const pageTitle = document.querySelector('[id$="-page-title"][tabindex="-1"], main h1[tabindex="-1"], main h2[tabindex="-1"]');
    pageTitle?.focus();
  });
}

function closeFromBackdrop(event) {
  if (event.target === event.currentTarget && props.closeOnBackdrop && !props.busy) emit('close');
}

watch(() => props.open, (open) => { if (open) openDialog(); else restoreDialog(); }, { immediate: true });
onBeforeUnmount(restoreDialog);
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop app-dialog-backdrop" @click.self="closeFromBackdrop">
      <section ref="dialog" class="app-dialog" role="dialog" aria-modal="true" :aria-labelledby="titleId" tabindex="-1">
        <slot />
      </section>
    </div>
  </Teleport>
</template>
