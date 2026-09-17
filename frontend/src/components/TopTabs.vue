<script lang="ts">
/** 全局顶部导航：品牌 + 主区 tab（对话 / 管理）。 */
export type MainNavKey = 'chat' | 'admin'
</script>

<script setup lang="ts">
defineProps<{ active: MainNavKey }>()
const emit = defineEmits<{ select: [key: MainNavKey] }>()

const tabs: { key: MainNavKey; label: string }[] = [
  { key: 'chat', label: '对话' },
  { key: 'admin', label: '管理' },
]
</script>

<template>
  <div class="top-tabs">
    <button class="brand" title="RAG Workbench" @click="emit('select', 'chat')">
      <!-- 签名标志：对角引用括号 + 圆点（检索证据 → 回答带出处） -->
      <span class="brand-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M4.5 9.5V6.3c0-1 .8-1.8 1.8-1.8h3.2" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
          <path d="M19.5 14.5v3.2c0 1-.8 1.8-1.8 1.8h-3.2" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
          <circle cx="12" cy="12" r="2.5" fill="currentColor" />
        </svg>
      </span>
      <span class="brand-name">RAG Workbench</span>
    </button>
    <nav class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ active: active === t.key }"
        @click="emit('select', t.key)"
      >{{ t.label }}</button>
    </nav>
    <div class="right">
      <slot name="right" />
    </div>
  </div>
</template>

<style scoped>
.top-tabs {
  flex: 1;
  display: flex;
  align-items: stretch;
  height: 100%;
  min-width: 0;
}
.brand {
  display: flex; align-items: center; gap: 9px;
  padding: 0 16px;
  border: none; background: none; cursor: pointer;
  flex-shrink: 0;
}
.brand-icon {
  width: 26px; height: 26px;
  display: flex; align-items: center; justify-content: center;
  background: var(--color-primary); color: #fff;
  border-radius: 7px;
}
.brand-icon svg { width: 16px; height: 16px; }
.brand-name {
  font-family: var(--font-display);
  font-size: 13px; font-weight: 700; letter-spacing: 0.01em;
  color: var(--color-ink); white-space: nowrap;
}
.tabs { display: flex; align-items: stretch; margin-left: 4px; min-width: 0; }
.tab {
  border: none; background: none;
  padding: 0 14px;
  font-size: 13.5px; font-weight: 500;
  color: var(--color-ink-secondary);
  cursor: pointer; position: relative;
  transition: color 0.15s;
  white-space: nowrap;
}
.tab:hover { color: var(--color-ink); }
.tab.active { color: var(--color-ink); font-weight: 600; }
.tab.active::after {
  content: '';
  position: absolute; left: 10px; right: 10px; bottom: 0;
  height: 2.5px; border-radius: 2px 2px 0 0;
  background: linear-gradient(90deg, var(--color-primary), var(--color-primary-hover));
}
.right { margin-left: auto; display: flex; align-items: center; gap: 12px; padding-right: 16px; }

@media (max-width: 768px) {
  .brand { padding: 0 10px; }
  .brand-name { display: none; }
  .tab { padding: 0 10px; font-size: 13px; }
  .right { padding-right: 10px; }
}
</style>
