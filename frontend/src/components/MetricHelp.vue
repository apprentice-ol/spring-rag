<script setup lang="ts">
/**
 * 指标名旁的「?」：这个指标到底在量什么、当前这个值算好还是坏。
 *
 * <p>内容取自 {@code METRIC_KNOWLEDGE}（与「指标解读」抽屉同一份事实源）——
 * 只在一处维护，抽屉和悬停不可能说法不一。没有收录的指标名不渲染图标，
 * 宁可没有入口，也不给一个点开是空的问号。</p>
 */
import { computed } from 'vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { METRIC_KNOWLEDGE, metricKey } from './evalShared'

const props = defineProps<{
  /** 指标名（可带 @k 后缀，如 recall_at_5） */
  name: string
  /** 当前值；给了才给出「这个值算好还是坏」的判读 */
  mean?: number
}>()

const know = computed(() => METRIC_KNOWLEDGE[metricKey(props.name)])
const judge = computed(() => (know.value && typeof props.mean === 'number'
  ? know.value.judge(props.mean)
  : ''))
</script>

<template>
  <a-popover v-if="know" placement="top" trigger="hover" :overlay-style="{ maxWidth: '380px' }">
    <QuestionCircleOutlined class="metric-help" @click.stop />
    <template #content>
      <div class="mh">
        <div class="mh-head">
          <b>{{ know.label }}</b>
          <span class="mh-stage">{{ know.stage }}</span>
          <span class="mh-dir">{{ know.dir }}</span>
        </div>
        <p class="mh-desc">{{ know.desc }}</p>
        <p v-if="judge" class="mh-judge">{{ judge }}</p>
      </div>
    </template>
  </a-popover>
</template>

<style scoped>
.metric-help {
  color: var(--color-ink-tertiary);
  font-size: 12px;
  cursor: help;
  margin-left: 3px;
}
.metric-help:hover { color: var(--color-primary); }

.mh { display: flex; flex-direction: column; gap: 6px; }
.mh-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mh-head b { font-size: 13px; }
.mh-stage, .mh-dir {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.14);
}
.mh-desc { margin: 0; font-size: 12px; line-height: 1.6; }
.mh-judge { margin: 0; font-size: 12px; line-height: 1.6; color: #ffd591; }
</style>
