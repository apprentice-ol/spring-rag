<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { CopyOutlined } from '@ant-design/icons-vue'
import { streamChat, type AgentTrace } from '../api/chat'
import { listDatasets, listItems, triggerRun, getRun, type EvalDataset } from '../api/eval'
import { activeParadigms, paradigmLabel, statusText } from './evalShared'
import { RUN_STATUS_COLOR, fmtLatency } from '../utils/format'
import { copyWithToast } from '../composables/useClipboard'
import AgentTraceTree from './AgentTraceTree.vue'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'
import MarkdownIt from 'markdown-it'

/** 用户可选范式（历史 naive/react 不进对照列表）；desc 作 checkbox 的 hover 说明 */
const paradigmOpts = activeParadigms()

const activeTab = ref<'live' | 'eval'>('live')

// ===== Live 并发对照 =====
const question = ref('RAG是什么')
const liveAgents = ref<string[]>(['knowledge', 'react_loop'])
const liveRunning = ref(false)
interface LiveColumn {
  trace: AgentTrace | null
  answer: string
  done: boolean
  error: string
  latency: number
  t0: number
}
const liveColumns = ref<Record<string, LiveColumn>>({})

/** Live 回答走 markdown 渲染（与聊天窗一致的观感；简单场景无需 hljs） */
const md = new MarkdownIt({ html: true, linkify: true, breaks: true })
function renderMd(t: string): string {
  return t ? md.render(t) : ''
}

async function runLive() {
  if (!question.value.trim() || liveAgents.value.length === 0) return
  liveRunning.value = true
  liveColumns.value = {}
  for (const a of liveAgents.value) {
    liveColumns.value[a] = { trace: null, answer: '', done: false, error: '', latency: 0, t0: Date.now() }
  }
  const batchId = Date.now()
  await Promise.all(
    liveAgents.value.map(async (agent) => {
      const col = liveColumns.value[agent]
      try {
        await streamChat(
          question.value,
          'compare-' + batchId + '-' + agent,
          {
            onTrace: (t) => {
              col.trace = t
            },
            onContent: (c) => {
              col.answer += c
            },
            onDone: () => {
              col.done = true
              col.latency = Date.now() - col.t0
            },
            onError: (e) => {
              col.error = String(e)
              col.done = true
            },
          },
          agent,
        )
      } catch (e) {
        col.error = String(e)
        col.done = true
      }
    }),
  )
  liveRunning.value = false
}

// ===== Eval 指标对照 =====
const datasets = ref<EvalDataset[]>([])
const datasetId = ref<number | undefined>()
const evalAgents = ref<string[]>(['knowledge', 'react_loop'])
const evalLimit = ref(5)
const evalRunning = ref(false)
interface EvalResult {
  runId: number
  status: string
  metrics: Record<string, { mean?: number; median?: number }> | null
}
const evalResults = ref<Record<string, EvalResult>>({})

onMounted(async () => {
  try {
    datasets.value = await listDatasets()
    if (datasets.value.length) datasetId.value = datasets.value[0].id
  } catch {
    /* ignore */
  }
})

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** 本次对照实际跑的题数（所有范式跑同一批题） */
const evalQuestionCount = ref<number | null>(null)

async function runEval() {
  if (!datasetId.value || evalAgents.value.length === 0) return
  evalRunning.value = true
  evalResults.value = {}
  evalQuestionCount.value = null

  // 固定同一批问题：先取数据集启用条目，前端抽样一次，所有范式复用同一组 itemId → 对照公平。
  // （原先每范式各自 triggerRun({limit})，后端各自随机 shuffle，跑的是不同题目子集，对照失真。）
  let itemIds: number[] | undefined
  try {
    const all = await listItems(datasetId.value)
    const enabled = all.filter((i) => i.enabled === 1)
    if (enabled.length === 0) {
      message.error('该数据集无启用条目，无法对照')
      evalRunning.value = false
      return
    }
    if (enabled.length > evalLimit.value) {
      const shuffled = enabled.slice().sort(() => Math.random() - 0.5)
      itemIds = shuffled.slice(0, evalLimit.value).map((i) => i.id)
    } else {
      itemIds = enabled.map((i) => i.id)
    }
    evalQuestionCount.value = itemIds.length
  } catch {
    // 取题失败：不限定 itemIds，后端按全量跑（仍可对照，但不再保证严格同题）
  }

  const runIds: Record<string, number> = {}
  for (const a of evalAgents.value) {
    try {
      const { runId } = await triggerRun(datasetId.value, {
        paradigm: a,
        itemIds,
      })
      runIds[a] = runId
      evalResults.value[a] = { runId, status: 'RUNNING', metrics: null }
    } catch (e: unknown) {
      // 透传后端报错（如「已有 N 个评测运行中」的并发上限提示），不再静默只标 FAILED
      message.error((e as { response?: { data?: { message?: string } } })?.response?.data?.message
        || `范式 ${paradigmLabel(a)} 触发失败`)
      evalResults.value[a] = { runId: -1, status: 'FAILED', metrics: null }
    }
  }
  await Promise.all(
    Object.entries(runIds).map(async ([agent, runId]) => {
      while (true) {
        await sleep(2500)
        try {
          const run = await getRun(runId)
          evalResults.value[agent].status = run.status
          if (run.status === 'DONE' || run.status === 'FAILED') {
            evalResults.value[agent].metrics = run.aggregateMetrics
              ? JSON.parse(run.aggregateMetrics)
              : {}
            break
          }
        } catch {
          break
        }
      }
    }),
  )
  evalRunning.value = false
}

function metricOf(agent: string, name: string): string {
  const m = evalResults.value[agent]?.metrics
  if (!m || !m[name]) return '—'
  return (m[name].mean ?? 0).toFixed(3)
}

const evalColumns = useResizableColumns([
  { title: '范式', dataIndex: 'paradigm', key: 'paradigm' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  { title: 'Recall@5', dataIndex: 'recall5', key: 'recall5' },
  { title: 'Precision@5', dataIndex: 'precision5', key: 'precision5' },
  { title: 'MRR', dataIndex: 'mrr', key: 'mrr' },
  { title: 'nDCG@5', dataIndex: 'ndcg5', key: 'ndcg5' },
])
const evalRows = computed(() =>
  evalAgents.value.map((a) => ({
    key: a,
    paradigm: paradigmLabel(a),
    status: evalResults.value[a]?.status ?? '',
    recall5: metricOf(a, 'recall_at_5'),
    precision5: metricOf(a, 'precision_at_5'),
    mrr: metricOf(a, 'mrr'),
    ndcg5: metricOf(a, 'ndcg_at_5'),
  })),
)
const hasEvalResult = computed(() => Object.keys(evalResults.value).length > 0)
</script>

<template>
  <div class="compare-panel page-scroll">
    <div class="page-header">
      <div>
        <span class="eyebrow">Agent Compare</span>
        <h2 class="page-title">Agent 对照</h2>
        <p class="page-desc">同一问题 / 同一批题目并发跑多个范式，横向对比回答过程与检索指标</p>
      </div>
    </div>

    <a-tabs v-model:activeKey="activeTab" class="compare-tabs">
      <!-- ===== Live 并发对照 ===== -->
      <a-tab-pane key="live" tab="Live 并发对照">
        <div class="filter-card">
          <div class="filter-row">
            <a-input
              v-model:value="question"
              placeholder="输入问题，并发跑选中的 agent 范式"
              class="w-xl"
              @press-enter="runLive"
            />
            <a-checkbox-group v-model:value="liveAgents" class="paradigm-checks">
              <a-tooltip v-for="p in paradigmOpts" :key="p.value" :title="p.desc">
                <a-checkbox :value="p.value">{{ p.label }}</a-checkbox>
              </a-tooltip>
            </a-checkbox-group>
            <div class="filter-actions">
              <a-button type="primary" :loading="liveRunning" @click="runLive">发送对照</a-button>
            </div>
          </div>
        </div>
        <div v-if="liveRunning || Object.keys(liveColumns).length" class="cols">
          <div v-for="a in liveAgents" :key="a" class="col">
            <div class="col-head">
              <a-tag color="purple" class="col-tag">{{ paradigmLabel(a) }}</a-tag>
              <span class="col-status">
                <span v-if="liveColumns[a]?.error" class="err">错误</span>
                <template v-else-if="liveColumns[a] && !liveColumns[a].done">运行中…</template>
                <template v-else-if="liveColumns[a]">
                  <span class="num">{{ fmtLatency(liveColumns[a].latency) }}</span>
                  · LLM ×{{ liveColumns[a].trace?.llmCallCount ?? 0 }}
                </template>
              </span>
              <button
                v-if="liveColumns[a]?.answer"
                class="copy-btn"
                title="复制回答"
                @click="copyWithToast(liveColumns[a].answer)"
              >
                <CopyOutlined />
              </button>
            </div>
            <AgentTraceTree :trace="liveColumns[a]?.trace ?? null" />
            <div class="answer">
              <div v-if="liveColumns[a]?.answer" class="markdown-body md-answer" v-html="renderMd(liveColumns[a].answer)"></div>
              <a-spin
                v-else-if="liveColumns[a] && !liveColumns[a].done && !liveColumns[a].error"
                size="small"
              />
              <span v-else-if="liveColumns[a]?.error" class="err-text">{{ liveColumns[a].error }}</span>
            </div>
          </div>
        </div>
        <div v-else class="filter-card live-empty">
          <a-empty description="输入问题并选择范式，点「发送对照」开始并发对比" />
        </div>
      </a-tab-pane>

      <!-- ===== Eval 指标对照 ===== -->
      <a-tab-pane key="eval" tab="Eval 指标对照">
        <div class="filter-card">
          <div class="filter-row">
            <a-select v-model:value="datasetId" class="w-lg" placeholder="选择数据集">
              <a-select-option v-for="d in datasets" :key="d.id" :value="d.id">
                {{ d.name }} ({{ d.itemCount }})
              </a-select-option>
            </a-select>
            <a-checkbox-group v-model:value="evalAgents" class="paradigm-checks">
              <a-tooltip v-for="p in paradigmOpts" :key="p.value" :title="p.desc">
                <a-checkbox :value="p.value">{{ p.label }}</a-checkbox>
              </a-tooltip>
            </a-checkbox-group>
            <span class="filter-label">抽样</span>
            <a-input-number v-model:value="evalLimit" :min="1" :max="50" class="w-xs" />
            <div class="filter-actions">
              <a-button type="primary" :loading="evalRunning" @click="runEval">触发对照</a-button>
            </div>
          </div>
        </div>
        <div class="table-card">
          <a-table
            :data-source="evalRows"
            :columns="evalColumns"
            :pagination="false"
            size="small"
            :loading="evalRunning"
            :scroll="{ x: 640 }"
          >
            <template #headerCell="{ column }">
              <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="evalColumns">{{ column.title }}</span>
            </template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag v-if="record.status" :color="RUN_STATUS_COLOR[record.status] || 'default'">
                  {{ statusText(record.status) }}
                </a-tag>
                <span v-else>—</span>
              </template>
              <span v-else-if="column.key !== 'paradigm'" class="num metric-cell">{{ record[column.key] }}</span>
            </template>
          </a-table>
          <p class="hint">
            所有范式跑<strong>同一批问题</strong>（{{ evalQuestionCount ?? evalLimit }} 题），指标为 mean（Recall@5 / Precision@5 / MRR / nDCG@5），保证对照公平。延迟与 LLM 调用次数见 Live 对照或单 run 详情。
          </p>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<style scoped>
.compare-tabs :deep(.ant-tabs-nav) { margin-bottom: 12px; }
/* 范式 checkbox（自定义 slot 渲染，组自带 hover 说明） */
.paradigm-checks { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: center; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.lbl {
  font-size: 13px;
  color: var(--color-ink-secondary);
}
.cols {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 8px;
}
.col {
  flex: 1;
  min-width: 320px;
  max-width: 460px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 12px;
}
.col-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.col-tag { margin: 0; }
.col-status {
  font-size: 11px;
  color: var(--color-ink-secondary);
  margin-left: auto;
}
.col-status .err { color: var(--color-danger); }
.copy-btn {
  border: none; background: none; cursor: pointer;
  color: var(--color-ink-tertiary); font-size: 12px;
  padding: 2px 6px; border-radius: var(--radius-sm);
  transition: color 0.15s, background 0.15s;
}
.copy-btn:hover { color: var(--color-primary); background: var(--color-primary-light); }
.answer {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--color-border);
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-ink);
  max-height: 320px;
  overflow-y: auto;
}
.md-answer { font-size: 13px; }
.md-answer :deep(p) { margin: 0 0 6px; }
.err-text {
  font-size: 12px;
  color: var(--color-danger);
  word-break: break-all;
}
.live-empty { display: flex; justify-content: center; padding: 48px 20px; }
.metric-cell { font-size: 12.5px; }
.hint {
  margin: 12px 20px 0;
  padding-bottom: 14px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
</style>
