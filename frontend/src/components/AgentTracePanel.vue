<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ReloadOutlined, SearchOutlined, EyeOutlined } from '@ant-design/icons-vue'
import { listAgentTraces, agentTraceStats, parseSteps, type AgentTraceRecord, type AgentTraceStat } from '../api/agentTrace'
import { PARADIGMS, paradigmLabel } from './evalShared'
import type { AgentTrace } from '../api/chat'
import AgentTraceTree from './AgentTraceTree.vue'

const records = ref<AgentTraceRecord[]>([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const filterParadigm = ref<string | undefined>(undefined)
const keyword = ref('')
const stats = ref<AgentTraceStat[]>([])
const selected = ref<AgentTrace | null>(null)

async function load() {
  loading.value = true
  try {
    const res = await listAgentTraces({
      page: page.value,
      size: size.value,
      paradigm: filterParadigm.value,
      keyword: keyword.value.trim() || undefined,
    })
    records.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}
async function loadStats() {
  try {
    stats.value = await agentTraceStats()
  } catch {
    /* ignore */
  }
}
onMounted(() => {
  load()
  loadStats()
})

function stepCount(r: AgentTraceRecord): number {
  return parseSteps(r.steps).length
}
/** 改写轨迹：retrieve 步的 input（query）序列 —— 看 react 多次检索的 query 怎么变 */
function rewriteTrail(r: AgentTraceRecord): string {
  const retrieves = parseSteps(r.steps)
    .filter((s) => s.action === 'retrieve')
    .map((s) => s.inputSummary)
  return retrieves.length ? retrieves.join('  →  ') : '—'
}
function fmtLatency(ms: number | null): string {
  if (ms == null) return '-'
  return ms < 1000 ? ms + 'ms' : (ms / 1000).toFixed(1) + 's'
}
function openDetail(r: AgentTraceRecord) {
  selected.value = {
    paradigm: r.paradigm,
    steps: parseSteps(r.steps),
    llmCallCount: r.llmCallCount ?? 0,
    startTimeMs: 0,
  } as AgentTrace
}
function onPage(p: number, s: number) {
  page.value = p
  size.value = s
  load()
}
function doSearch() {
  page.value = 1
  load()
}
function doReset() {
  filterParadigm.value = undefined
  keyword.value = ''
  page.value = 1
  load()
}

const columns = [
  { title: '范式', dataIndex: 'paradigm', key: 'paradigm', width: 110 },
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '步数', key: 'stepCount', width: 70, align: 'center' as const },
  { title: '改写轨迹（retrieve 的 query 序列）', key: 'trail', ellipsis: true },
  { title: 'LLM', key: 'llm', width: 60, align: 'center' as const },
  { title: '耗时', key: 'latency', width: 80 },
  { title: '时间', dataIndex: 'createTime', key: 'time', width: 160 },
  { title: '操作', key: 'action', width: 80 },
]
</script>

<template>
  <div class="trace-panel">
    <div class="panel-header">
      <h3 class="panel-title">Agent 轨迹分析</h3>
      <p class="panel-desc">线上 chat 的 agent 思考流程（thought/action）落库于此——看多步决策规律（如 react 拼写纠错/关键词重组），反哺 naive</p>
    </div>

    <!-- 按范式统计 -->
    <div v-if="stats.length" class="stat-row">
      <div v-for="s in stats" :key="s.paradigm" class="stat-card">
        <span class="stat-name">{{ paradigmLabel(s.paradigm) }}</span>
        <span class="stat-cnt">{{ s.cnt }} 条</span>
        <span class="stat-avg">均 {{ Number(s.avg_steps).toFixed(1) }} 步</span>
      </div>
    </div>

    <!-- 查询 -->
    <div class="query-bar">
      <a-select v-model:value="filterParadigm" placeholder="全部范式" allow-clear style="width: 140px" @change="doSearch">
        <a-select-option v-for="p in PARADIGMS" :key="p.value" :value="p.value">{{ p.label }}</a-select-option>
      </a-select>
      <a-input v-model:value="keyword" placeholder="问题关键词" allow-clear style="width: 200px" @press-enter="doSearch">
        <template #prefix><SearchOutlined /></template>
      </a-input>
      <a-button type="primary" @click="doSearch">
        <template #icon><SearchOutlined /></template>查询
      </a-button>
      <a-button @click="doReset">
        <template #icon><ReloadOutlined /></template>重置
      </a-button>
    </div>

    <a-table
      :data-source="records"
      :columns="columns"
      :loading="loading"
      :pagination="false"
      size="middle"
      row-key="id"
      :scroll="{ x: 1000 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'paradigm'">
          <a-tag color="teal">{{ paradigmLabel(record.paradigm) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'stepCount'">{{ stepCount(record) }}</template>
        <template v-else-if="column.key === 'trail'">
          <a-tooltip :title="rewriteTrail(record)"><span class="trail">{{ rewriteTrail(record) }}</span></a-tooltip>
        </template>
        <template v-else-if="column.key === 'llm'">{{ record.llmCallCount ?? '-' }}</template>
        <template v-else-if="column.key === 'latency'">{{ fmtLatency(record.totalLatencyMs) }}</template>
        <template v-else-if="column.key === 'time'">{{ record.createTime?.replace('T', ' ').slice(0, 19) }}</template>
        <template v-else-if="column.key === 'action'">
          <a-button type="link" size="small" @click="openDetail(record)"><EyeOutlined />查看</a-button>
        </template>
      </template>
      <template #emptyText><a-empty description="暂无轨迹——在聊天页发几条消息后这里会出现" /></template>
    </a-table>

    <div class="pager">
      <span class="pager-hint">共 {{ total }} 条</span>
      <a-pagination
        :current="page"
        :page-size="size"
        :total="total"
        :show-size-changer="true"
        :page-size-options="['10', '20', '50']"
        @change="onPage"
      />
    </div>

    <!-- 详情抽屉：复用 AgentTraceTree 渲染思考流程 -->
    <a-drawer :open="selected !== null" :width="560" title="思考流程" @update:open="(v: boolean) => { if (!v) selected = null }">
      <AgentTraceTree v-if="selected" :trace="selected" />
    </a-drawer>
  </div>
</template>

<style scoped>
.trace-panel {
  padding: 20px;
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  overflow-y: auto;
}
.panel-title { margin: 0; font-size: 15px; font-weight: 600; }
.panel-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-ink-tertiary); }
.stat-row { display: flex; gap: 10px; flex-wrap: wrap; }
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: 8px;
  min-width: 110px;
}
.stat-name { font-size: 13px; font-weight: 600; color: var(--color-primary); }
.stat-cnt { font-size: 18px; font-weight: 700; color: var(--color-ink); }
.stat-avg { font-size: 11px; color: var(--color-ink-tertiary); }
.query-bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.trail { color: var(--color-ink-secondary); font-size: 12px; }
.pager { display: flex; justify-content: flex-end; align-items: center; gap: 16px; }
.pager-hint { font-size: 12px; color: var(--color-ink-tertiary); margin-right: auto; }
</style>
