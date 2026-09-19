<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ReloadOutlined, SearchOutlined, EyeOutlined, CopyOutlined } from '@ant-design/icons-vue'
import { listAgentTraces, agentTraceStats, parseSteps, toAgentTrace, type AgentTraceRecord, type AgentTraceStat } from '../api/agentTrace'
import { traceDetailUrl } from '../api/eval'
import { copyWithToast } from '../composables/useClipboard'
import { activeParadigms, paradigmLabel } from './evalShared'
import { fmtLatency, fmtTime } from '../utils/format'
import type { AgentTrace } from '../api/chat'
import AgentTraceTree from './AgentTraceTree.vue'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'

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
/** 检索轨迹：检索步的 query 序列（旧名 retrieve / 新图节点 kb_retrieve、react_*_act）——看多轮检索的 query 怎么变 */
const RETRIEVE_ACTIONS = ['retrieve', 'kb_retrieve']
function rewriteTrail(r: AgentTraceRecord): string {
  const steps = parseSteps(r.steps)
  const retrieves = steps
    .filter((s) => RETRIEVE_ACTIONS.includes(s.action) || (s.action.endsWith('_act') && s.inputSummary))
    .map((s) => s.inputSummary)
    .filter((q): q is string => !!q)
  return retrieves.length ? retrieves.join('  →  ') : '—'
}
function openDetail(r: AgentTraceRecord) {
  selected.value = toAgentTrace(r)
}

/** 点击 traceId 跳 OpenObserve 该次请求完整链路（按轨迹落库时间生成 ±10min 窗口） */
function openObs(r: AgentTraceRecord) {
  if (!r.traceId) return
  const ts = r.createTime ? new Date(r.createTime).getTime() : undefined
  const url = traceDetailUrl(r.traceId, ts)
  if (url) window.open(url, '_blank')
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

const columns = useResizableColumns([
  { title: '范式', dataIndex: 'paradigm', key: 'paradigm', width: 180 },
  { title: '问题', dataIndex: 'question', key: 'question', width: 280, ellipsis: true },
  { title: '步数', key: 'stepCount', width: 70, align: 'center' as const },
  { title: '检索轨迹（query 序列）', key: 'trail', width: 240, ellipsis: true },
  { title: 'LLM', key: 'llm', width: 60, align: 'center' as const },
  { title: '耗时', key: 'latency', width: 80 },
  { title: 'traceId', dataIndex: 'traceId', key: 'traceId', width: 250, ellipsis: true },
  { title: '时间', dataIndex: 'createTime', key: 'time', width: 160 },
  { title: '操作', key: 'action', width: 80 },
])
</script>

<template>
  <div class="trace-panel page-scroll">
    <!-- 页头 -->
    <div class="page-header">
      <div class="page-header-text">
        <span class="eyebrow">Agent Trace</span>
        <h1 class="page-title">Agent 轨迹分析</h1>
        <p class="page-desc">线上 chat 的 agent 执行轨迹（图节点逐步落库：抽槽/问齐/思考/工具/裁决/收尾）——看多步决策规律与各阶段耗时分布</p>
      </div>
    </div>

    <!-- 按范式统计（小 KPI 卡） -->
    <div v-if="stats.length" class="stat-row">
      <div v-for="s in stats" :key="s.paradigm" class="stat-card">
        <span class="stat-name">{{ paradigmLabel(s.paradigm) }}</span>
        <span class="stat-cnt">{{ s.cnt }} 条</span>
        <span class="stat-avg">均 {{ Number(s.avg_steps).toFixed(1) }} 步</span>
      </div>
    </div>

    <!-- 筛选卡：范式 / 关键词 -->
    <div class="filter-card">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">范式</span>
          <a-select v-model:value="filterParadigm" placeholder="全部范式" allow-clear class="w-sm" @change="doSearch">
            <a-select-option v-for="p in activeParadigms()" :key="p.value" :value="p.value">
              {{ p.label }} · {{ p.desc }}
            </a-select-option>
          </a-select>
        </div>
        <div class="filter-item">
          <span class="filter-label">关键词</span>
          <a-input v-model:value="keyword" placeholder="问题关键词" allow-clear class="w-md" @press-enter="doSearch">
            <template #prefix><SearchOutlined /></template>
          </a-input>
        </div>
        <div class="filter-actions">
          <a-button type="primary" @click="doSearch">查询</a-button>
          <a-button @click="doReset">重置</a-button>
        </div>
      </div>
    </div>

    <!-- 表格卡：工具栏 + 轨迹表 + 卡底分页 -->
    <div class="table-card">
      <div class="table-toolbar">
        <span class="toolbar-hint">共 {{ total }} 条轨迹</span>
        <div class="toolbar-right">
          <a-button type="text" size="small" title="刷新" @click="load">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </div>
      </div>

      <a-table
        :data-source="records"
        :columns="columns"
        :loading="loading"
        :pagination="false"
        size="middle"
        row-key="id"
        :scroll="{ x: 1400 }"
      >
        <template #headerCell="{ column }">
          <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="columns">{{ column.title }}</span>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'paradigm'">
            <a-tag color="purple" class="paradigm-tag" :title="paradigmLabel(record.paradigm)">
              {{ paradigmLabel(record.paradigm) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'stepCount'"><span class="num">{{ stepCount(record) }}</span></template>
          <template v-else-if="column.key === 'trail'">
            <a-tooltip :title="rewriteTrail(record)"><span class="trail">{{ rewriteTrail(record) }}</span></a-tooltip>
          </template>
          <template v-else-if="column.key === 'llm'"><span class="num">{{ record.llmCallCount ?? '—' }}</span></template>
          <template v-else-if="column.key === 'latency'"><span class="num">{{ fmtLatency(record.totalLatencyMs) }}</span></template>
          <template v-else-if="column.key === 'traceId'">
            <template v-if="record.traceId">
              <a-tooltip :title="'点击查看 OpenObserve 完整链路\n' + record.traceId">
                <a class="trace-id" @click="openObs(record)">{{ record.traceId }}</a>
              </a-tooltip>
              <a-button type="text" size="small" class="trace-copy" title="复制 traceId" @click="copyWithToast(record.traceId)">
                <template #icon><CopyOutlined /></template>
              </a-button>
            </template>
            <span v-else class="trace-id-muted">—</span>
          </template>
          <template v-else-if="column.key === 'time'"><span class="num">{{ fmtTime(record.createTime) }}</span></template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openDetail(record)"><EyeOutlined />查看</a-button>
          </template>
        </template>
        <template #emptyText><a-empty description="暂无轨迹——在聊天页发几条消息后这里会出现" /></template>
      </a-table>

      <div class="table-footer">
        <span class="toolbar-hint">共 {{ total }} 条</span>
        <a-pagination
          :current="page"
          :page-size="size"
          :total="total"
          :show-total="(t: number) => `共 ${t} 条`"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
          @change="onPage"
        />
      </div>
    </div>

    <!-- 详情抽屉：复用 AgentTraceTree 渲染思考流程 -->
    <a-drawer :open="selected !== null" :width="560" title="思考流程" @update:open="(v: boolean) => { if (!v) selected = null }">
      <AgentTraceTree v-if="selected" :trace="selected" />
    </a-drawer>
  </div>
</template>

<style scoped>
/* 按范式统计小卡（页头与筛选卡之间，补下边距对齐全局节奏） */
.stat-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  min-width: 110px;
}
.stat-name { font-size: 13px; font-weight: 600; color: var(--color-primary); }
.stat-cnt {
  font-size: 18px;
  font-weight: 700;
  color: var(--color-ink);
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
}
.stat-avg { font-size: 11px; color: var(--color-ink-tertiary); }
.trail { color: var(--color-ink-secondary); font-size: 12px; }
/* 范式标签：列再窄也不挤压「问题」列（超长标签省略号 + title 兜底） */
.paradigm-tag {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.trace-id {
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--color-primary);
  cursor: pointer;
  word-break: break-all;
}
.trace-id:hover { text-decoration: underline; }
.trace-copy { color: var(--color-ink-tertiary); }
.trace-copy:hover { color: var(--color-primary); }
.trace-id-muted { color: var(--color-ink-tertiary); }
</style>
