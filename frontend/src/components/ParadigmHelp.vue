<script setup lang="ts">
/**
 * 范式选择器旁的「?」：把每个范式实际跑的是什么说清楚。
 *
 * <p>这些说明原先塞在下拉选项里（`label · desc`），而评测页那个 select 只有 180px——
 * 一段几十字的描述挤在窄框里，跟相邻内容糊成一片。说明本身是有用的，
 * 错的是放置位置：选项行只该承担"选哪个"，解释交给悬停展开。</p>
 */
import { QuestionCircleOutlined } from '@ant-design/icons-vue'

defineProps<{
  options: { value: string; label: string; desc: string }[]
}>()
</script>

<template>
  <a-popover placement="topLeft" trigger="hover" :overlay-style="{ maxWidth: '460px' }">
    <QuestionCircleOutlined class="paradigm-help" />
    <template #content>
      <div class="ph-list">
        <div v-for="p in options" :key="p.value || 'auto'" class="ph-row">
          <span class="ph-name">{{ p.label }}</span>
          <span class="ph-desc">{{ p.desc }}</span>
        </div>
      </div>
    </template>
  </a-popover>
</template>

<style scoped>
.paradigm-help {
  color: var(--color-ink-tertiary);
  font-size: 13px;
  cursor: help;
  margin-left: 2px;
}
.paradigm-help:hover {
  color: var(--color-primary);
}

.ph-list { display: flex; flex-direction: column; gap: 8px; }
.ph-row { display: flex; flex-direction: column; gap: 2px; }
.ph-name { font-weight: 600; font-size: 12px; }
.ph-desc { font-size: 12px; line-height: 1.55; color: var(--color-ink-secondary); }
</style>
