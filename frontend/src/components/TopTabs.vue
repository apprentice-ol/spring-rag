<script lang="ts">
/** 全局顶部导航：品牌 + 主区 tab（对话 / 知识库 / 管理）。 */
export type MainNavKey = 'chat' | 'knowledge' | 'admin'
</script>

<script setup lang="ts">
import { ApiOutlined } from '@ant-design/icons-vue'

defineProps<{ active: MainNavKey }>()
const emit = defineEmits<{ select: [key: MainNavKey] }>()

const tabs: { key: MainNavKey; label: string }[] = [
  { key: 'chat', label: '对话' },
  { key: 'knowledge', label: '知识库' },
  { key: 'admin', label: '管理' },
]
</script>

<template>
  <div class="top-tabs">
    <button class="brand" title="RAG Workbench" @click="emit('select', 'chat')">
      <span class="brand-icon"><ApiOutlined /></span>
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
  border-radius: var(--radius-sm); font-size: 14px;
}
.brand-name {
  font-family: var(--font-display);
  font-size: 13px; font-weight: 600; letter-spacing: 0.01em;
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
.tab.active { color: var(--color-ink); }
.tab.active::after {
  content: '';
  position: absolute; left: 10px; right: 10px; bottom: 0;
  height: 2px; border-radius: 1px 1px 0 0;
  background: var(--color-primary);
}
.right { margin-left: auto; display: flex; align-items: center; gap: 12px; padding-right: 16px; }

@media (max-width: 768px) {
  .brand { padding: 0 10px; }
  .brand-name { display: none; }
  .tab { padding: 0 10px; font-size: 13px; }
  .right { padding-right: 10px; }
}
</style>
