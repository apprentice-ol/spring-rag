<script setup lang="ts">
import { ref } from 'vue'
import type { AgentTrace, RetrieveDetail, GradeDetail, RerankDetail } from '../api/chat'

defineProps<{ trace: AgentTrace | null }>()

// action → 颜色（antdv Tag/Timeline 色板）
const actionColor: Record<string, string> = {
  retrieve: 'blue',
  grade: 'orange',
  rewrite: 'cyan',
  decompose: 'purple',
  rerank: 'geekblue',
  reflect: 'gold',
  route: 'green',
  plan: 'purple',
  execute: 'blue',
  finish: 'green',
  merge: 'lime',
  web_search: 'magenta',
  think: 'default',
  error: 'red',
}

const verdictText: Record<string, string> = {
  ALL_RELEVANT: '全相关',
  PARTIAL: '部分相关',
  ALL_IRRELEVANT: '全无关',
}

// 折叠展开状态（按 stepIndex）；用替换 Set 的方式触发响应式
const expanded = ref<Set<number>>(new Set())
function toggle(idx: number) {
  const next = new Set(expanded.value)
  if (next.has(idx)) {
    next.delete(idx)
  } else {
    next.add(idx)
  }
  expanded.value = next
}
function isExpanded(idx: number) {
  return expanded.value.has(idx)
}

// detail 联合类型守卫（template 里访问子字段需要收窄）
function isRetrieve(d: unknown): d is RetrieveDetail {
  return !!d && typeof (d as RetrieveDetail).query === 'string' && Array.isArray((d as RetrieveDetail).chunks)
}
function isGrade(d: unknown): d is GradeDetail {
  return !!d && Array.isArray((d as GradeDetail).grades)
}
function isRerank(d: unknown): d is RerankDetail {
  return !!d && Array.isArray((d as RerankDetail).before) && Array.isArray((d as RerankDetail).after)
}

function fmt(n: number | null | undefined): string {
  return n == null ? '-' : n.toFixed(2)
}

function droppedRefs(detail: RerankDetail): number[] {
  const keep = new Set(detail.after.map((a) => a.ref))
  return detail.before.filter((r) => !keep.has(r))
}
</script>

<template>
  <div v-if="trace" class="agent-trace">
    <div class="trace-meta">
      <a-tag color="purple">{{ trace.paradigm }}</a-tag>
      <span class="meta-item">{{ trace.steps.length }} 步</span>
      <span class="meta-item">LLM ×{{ trace.llmCallCount }}</span>
      <span v-if="trace.totalLatencyMs" class="meta-item">{{ trace.totalLatencyMs }}ms</span>
    </div>
    <a-timeline>
      <a-timeline-item
        v-for="s in trace.steps"
        :key="s.stepIndex"
        :color="actionColor[s.action] || 'gray'"
      >
        <div class="step-head">
          <a-tag :color="actionColor[s.action] || 'default'" class="step-tag">{{ s.action }}</a-tag>
          <span class="step-latency">{{ s.latencyMs }}ms</span>
          <span v-if="s.detail" class="expand-btn" @click="toggle(s.stepIndex)">
            {{ isExpanded(s.stepIndex) ? '▾ 收起' : '▸ 展开' }}
          </span>
        </div>
        <div v-if="s.thought" class="step-thought">{{ s.thought }}</div>
        <div v-if="s.inputSummary" class="step-io"><span class="io-label">in:</span> {{ s.inputSummary }}</div>
        <div v-if="s.outputSummary" class="step-io"><span class="io-label">out:</span> {{ s.outputSummary }}</div>

        <!-- 结构化产物详情（折叠展开） -->
        <div v-if="s.detail && isExpanded(s.stepIndex)" class="step-detail">
          <!-- retrieve：命中的 chunks -->
          <table v-if="isRetrieve(s.detail)" class="detail-table">
            <thead>
              <tr>
                <th>ref</th><th>分数</th><th>原始</th><th>来源文档</th><th>通道</th><th class="wide-col">预览</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in s.detail.chunks" :key="c.ref">
                <td>{{ c.ref }}</td>
                <td>{{ fmt(c.score) }}</td>
                <td>{{ fmt(c.originalScore) }}</td>
                <td>{{ c.docName }}</td>
                <td>{{ c.channel }}</td>
                <td class="text-cell">{{ c.preview }}</td>
              </tr>
            </tbody>
          </table>

          <!-- grade：逐条评分理由 -->
          <template v-else-if="isGrade(s.detail)">
            <div class="grade-meta">
              <a-tag :color="s.detail.verdict === 'ALL_IRRELEVANT' ? 'red' : 'green'">
                {{ verdictText[s.detail.verdict] || s.detail.verdict }}
              </a-tag>
              <span>相关 {{ s.detail.relevant }}/{{ s.detail.total }}</span>
              <span>均分 {{ s.detail.avgScore.toFixed(2) }}</span>
            </div>
            <table class="detail-table">
              <thead>
                <tr><th>ref</th><th>分数</th><th>相关?</th><th class="wide-col">理由</th></tr>
              </thead>
              <tbody>
                <tr v-for="g in s.detail.grades" :key="g.ref" :style="g.relevant ? '' : 'opacity:0.45'">
                  <td>{{ g.ref }}</td>
                  <td>{{ g.score.toFixed(2) }}</td>
                  <td>{{ g.relevant ? '✓' : '✗' }}</td>
                  <td class="text-cell">{{ g.reason || '—' }}</td>
                </tr>
              </tbody>
            </table>
          </template>

          <!-- rerank：重排前/保留/淘汰 -->
          <div v-else-if="isRerank(s.detail)" class="rerank-detail">
            <div class="rerank-line">
              <span class="rerank-label">重排前 {{ s.detail.before.length }} 条</span>
              <span v-for="r in s.detail.before" :key="'b' + r" class="ref-chip">{{ r }}</span>
            </div>
            <div class="rerank-line">
              <span class="rerank-label">保留 {{ s.detail.after.length }} 条</span>
              <span
                v-for="(r, i) in s.detail.after"
                :key="'a' + i"
                class="ref-chip keep"
              >{{ r.ref < 0 ? '—' : r.ref }}<small>{{ fmt(r.score) }}</small></span>
            </div>
            <div v-if="droppedRefs(s.detail).length" class="rerank-line">
              <span class="rerank-label">淘汰</span>
              <span v-for="r in droppedRefs(s.detail)" :key="'d' + r" class="ref-chip drop">{{ r }}</span>
            </div>
          </div>
        </div>
      </a-timeline-item>
    </a-timeline>
  </div>
  <a-empty v-else description="等待执行轨迹…" :image="undefined" style="padding:16px 0" />
</template>

<style scoped>
.agent-trace { padding: 4px 2px; }
.trace-meta {
  display: flex; align-items: center; gap: 10px;
  margin-bottom: 12px; font-size: 12px; color: var(--color-ink-secondary);
}
.step-head { display: flex; align-items: center; gap: 8px; margin-bottom: 2px; }
.step-tag { margin: 0; }
.step-latency { font-size: 11px; color: var(--color-ink-tertiary); }
.expand-btn {
  margin-left: auto; font-size: 11px; cursor: pointer; user-select: none;
  color: var(--color-primary);
}
.step-thought { font-size: 12px; color: var(--color-ink); margin: 2px 0; line-height: 1.5; }
.step-io { font-size: 11px; color: var(--color-ink-secondary); margin-top: 1px; word-break: break-all; }
.io-label { color: var(--color-ink-tertiary); }

.step-detail {
  margin-top: 6px; padding: 6px 8px; border-radius: var(--radius-sm);
  background: var(--color-surface-secondary);
}
.detail-table { width: 100%; border-collapse: collapse; font-size: 11px; table-layout: auto; }
.detail-table th {
  text-align: left; font-weight: normal; padding: 2px 6px;
  color: var(--color-ink-tertiary); white-space: nowrap;
}
.detail-table td {
  padding: 2px 6px; vertical-align: top;
  border-top: 1px solid var(--color-border-light);
}
.text-cell, .wide-col { max-width: 240px; color: var(--color-ink-secondary); word-break: break-all; }

.grade-meta {
  display: flex; gap: 10px; align-items: center;
  font-size: 11px; color: var(--color-ink-secondary); margin-bottom: 4px;
}

.rerank-detail { font-size: 11px; }
.rerank-line {
  margin: 3px 0; display: flex; flex-wrap: wrap; align-items: center; gap: 4px;
}
.rerank-label { color: var(--color-ink-tertiary); margin-right: 4px; }
.ref-chip {
  display: inline-flex; align-items: baseline; gap: 2px;
  padding: 1px 7px; border-radius: 10px; font-size: 11px;
  background: var(--color-surface-secondary);
}
.ref-chip small { font-size: 9px; color: var(--color-ink-tertiary); }
.ref-chip.keep { color: var(--color-success); }
.ref-chip.drop { opacity: 0.4; text-decoration: line-through; }
</style>
