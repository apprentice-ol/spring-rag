<script setup lang="ts">
/**
 * 检索命中明细表：ref / 分数 / 原始 / 来源文档 / 通道 / 引用 / 预览。
 *
 * <p>列口径与版式对齐 agent-framework 的 HitTable。表格胜在「比较与扫读」：分数、通道、
 * 来源落在同一行上，一眼看得出谁高谁低、哪一条是两路都命中。卡片适合讲故事，
 * 不适合看十连命中。</p>
 *
 * <p><b>为什么必须 table-layout: fixed</b>：默认的 auto 布局会按内容反推列宽——
 * 一条长文档名就能把「分数」列压成两行、把「通道」挤没，十条命中各挤各的，
 * 整张表失去对齐感，也就失去了扫读的价值。锁死布局 + 每列给定额宽度之后，
 * 宽度分配与内容无关；放不下的部分一律单行省略、悬停看全，
 * 预览列固定吃掉剩余宽度、默认两行截断、点一下放开。</p>
 */
import { ref } from 'vue'
import type { ChunkRow } from '../utils/traceAnalysis'

const props = defineProps<{
  chunks: ChunkRow[]
  /** 被正文引用的 ref 集合（「被引用」列与行高亮的判定源） */
  citedRefs: Set<number>
}>()

/** 展开全文的那一行（ref 编号；0 = 没有展开的）。一次只放开一条，免得十连展开撑爆抽屉。 */
const expandedRef = ref(0)

function toggleRow(ref: number | undefined) {
  const key = ref ?? 0
  expandedRef.value = expandedRef.value === key ? 0 : key
}

/** 分数两位小数；缺失写「—」而不是 0——「没记录」与「是 0 分」是两回事。 */
function num(value?: number): string {
  return typeof value === 'number' ? value.toFixed(2) : '—'
}

/** 文档名下方的章节路径（后端给的是 outline_path/heading，缺失则不占位）。 */
function headingOf(chunk: ChunkRow): string {
  return typeof chunk.heading === 'string' ? chunk.heading : ''
}

/** 预览够长才给展开手势：短内容点了没反应比不能点更让人困惑。 */
function previewExpandable(chunk: ChunkRow): boolean {
  return (chunk.content ?? '').length > 60
}

/** 双通道命中说明两路都召回，相关性更强（与 agent-framework 的通道着色同义）。 */
function channelBoost(chunk: ChunkRow): boolean {
  return (chunk.channel ?? '').includes('+')
}
</script>

<template>
  <div class="hits-wrap">
    <table class="detail-table">
      <thead>
        <tr>
          <th class="col-ref">ref</th>
          <th class="col-num">分数</th>
          <th class="col-num">原始</th>
          <th class="col-doc">来源文档</th>
          <th class="col-channel">通道</th>
          <th class="col-cited">引用</th>
          <th class="col-preview">预览</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="c in chunks"
          :key="c.ref"
          :class="{ cited: citedRefs.has(c.ref ?? -1), expandable: previewExpandable(c) }"
        >
          <td class="col-ref mono">{{ c.ref }}</td>
          <td class="col-num mono">{{ num(c.score) }}</td>
          <td class="col-num mono muted">{{ num(c.originalScore) }}</td>
          <td class="col-doc">
            <div class="doc-name" :title="c.docName ?? ''">{{ c.docName ?? '—' }}</div>
            <div v-if="headingOf(c)" class="doc-heading" :title="headingOf(c)">{{ headingOf(c) }}</div>
          </td>
          <td class="col-channel" :class="{ boost: channelBoost(c) }" :title="c.channel ?? ''">
            {{ c.channel ?? '—' }}
          </td>
          <td class="col-cited nowrap cited-col">{{ citedRefs.has(c.ref ?? -1) ? '✓ 被引用' : '' }}</td>
          <td
            class="col-preview preview-cell"
            :class="{ open: expandedRef === c.ref }"
            :title="expandedRef === c.ref ? '' : (c.content ?? '')"
            @click="previewExpandable(c) && toggleRow(c.ref)"
          >
            <div class="preview-body">{{ c.content ?? '' }}</div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
/* 卡片化（边框圆角 + 限高滚动），被引用行高亮 */
.hits-wrap {
  margin-top: 8px;
  padding: 6px 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  max-height: 320px;
  overflow: auto;
}

/* fixed：列宽由表头声明说了算，与内容长短无关——见脚本顶部的取舍说明 */
.detail-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 12px;
}

.detail-table th {
  padding: 0 8px 5px;
  text-align: left;
  font-weight: 500;
  font-size: 11px;
  color: var(--color-ink-tertiary);
  white-space: nowrap;
  border-bottom: 1px solid var(--color-border);
}
.detail-table td {
  padding: 7px 8px;
  vertical-align: top;
  border-bottom: 1px solid var(--color-border-light);
  overflow: hidden;
}

/* 定宽列：窄到够用即可，剩下的全部让给预览 */
.col-ref { width: 34px; }
.col-num { width: 52px; }
.col-doc { width: 132px; }
.col-channel { width: 68px; }
.col-cited { width: 64px; }
/* 预览吃掉剩余宽度 */
.col-preview { width: auto; }

/* 等宽用 --font-display（本仓的 JetBrains Mono 令牌；没有 --font-mono 这个名字） */
.mono { font-family: var(--font-display, monospace); font-variant-numeric: tabular-nums; }
.muted { color: var(--color-ink-tertiary); }
.nowrap { white-space: nowrap; }

.doc-name,
.doc-heading {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-name { color: var(--color-ink); }
.doc-heading { margin-top: 1px; font-size: 10px; color: var(--color-ink-tertiary); }

.col-channel { font-size: 11px; color: var(--color-ink-tertiary); }
.col-channel.boost { color: var(--color-success, #389e0d); }

.detail-table tbody tr:hover td { background: var(--color-surface-secondary); }

/* 预览：默认两行截断；点开那一行后放开（限高内滚动，免得十连展开撑爆抽屉） */
.preview-cell { color: var(--color-ink-secondary); cursor: default; }
.detail-table tr.expandable .preview-cell { cursor: pointer; }
.preview-body {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.5;
}
.preview-cell.open .preview-body {
  display: block;
  -webkit-line-clamp: unset;
  line-clamp: unset;
  max-height: 220px;
  overflow-y: auto;
}

/* 被引用行：浅底 + 那一格加重（按列类选中，不按 :last-child——末尾列的含义会随加列改变） */
.detail-table tr.cited td { background: rgba(82, 196, 26, 0.06); }
.detail-table tr.cited td.cited-col { color: var(--color-success, #389e0d); font-weight: 600; }
</style>
