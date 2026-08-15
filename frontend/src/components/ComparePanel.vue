<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { streamChat, type AgentTrace } from '../api/chat'
import { listDatasets, listItems, triggerRun, getRun, type EvalDataset } from '../api/eval'
import { PARADIGMS, paradigmLabel } from './evalShared'
import AgentTraceTree from './AgentTraceTree.vue'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'

const paradigmOpts = PARADIGMS.map(p => ({ label: p.label, value: p.value }))

const activeTab = ref<'live' | 'eval'>('live')

// ===== Live 并发对照 =====
const question = ref('RAG是什么')
const liveAgents = ref<string[]>(['naive', 'react'])
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
const evalAgents = ref<string[]>(['naive', 'react'])
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
    } catch {
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
  if (!m || !m[name]) return '-'
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
    status: evalResults.value[a]?.status ?? '-',
    recall5: metricOf(a, 'recall_at_5'),
    precision5: metricOf(a, 'precision_at_5'),
    mrr: metricOf(a, 'mrr'),
    ndcg5: metricOf(a, 'ndcg_at_5'),
  })),
)
</script>

<template>
  <div class="compare-panel">
    <a-tabs v-model:activeKey="activeTab">
      <!-- ===== Live 并发对照 ===== -->
      <a-tab-pane key="live" tab="Live 并发对照">
        <div class="toolbar">
          <a-input
            v-model:value="question"
            placeholder="输入问题，并发跑选中的 agent 范式"
            style="max-width: 360px"
            @press-enter="runLive"
          />
          <a-checkbox-group v-model:value="liveAgents" :options="paradigmOpts" />
          <a-button type="primary" :loading="liveRunning" @click="runLive">发送对照</a-button>
        </div>
        <div class="cols">
          <div v-for="a in liveAgents" :key="a" class="col">
            <div class="col-head">
              <a-tag color="purple">{{ paradigmLabel(a) }}</a-tag>
              <span class="col-status">
                <template v-if="liveColumns[a]?.error" class="err">错误</template>
                <template v-else-if="liveColumns[a] && !liveColumns[a].done">运行中…</template>
                <template v-else-if="liveColumns[a]">
                  {{ liveColumns[a].latency }}ms · LLM ×{{ liveColumns[a].trace?.llmCallCount ?? 0 }}
                </template>
              </span>
            </div>
            <AgentTraceTree :trace="liveColumns[a]?.trace ?? null" />
            <div class="answer">
              <template v-if="liveColumns[a]?.answer">{{ liveColumns[a].answer }}</template>
              <a-spin
                v-else-if="liveColumns[a] && !liveColumns[a].done && !liveColumns[a].error"
                size="small"
              />
            </div>
          </div>
        </div>
      </a-tab-pane>

      <!-- ===== Eval 指标对照 ===== -->
      <a-tab-pane key="eval" tab="Eval 指标对照">
        <div class="toolbar">
          <a-select
            v-model:value="datasetId"
            style="width: 220px"
            placeholder="选择数据集"
          >
            <a-select-option v-for="d in datasets" :key="d.id" :value="d.id">
              {{ d.name }} ({{ d.itemCount }})
            </a-select-option>
          </a-select>
          <a-checkbox-group v-model:value="evalAgents" :options="paradigmOpts" />
          <span class="lbl">抽样</span>
          <a-input-number v-model:value="evalLimit" :min="1" :max="50" style="width: 80px" />
          <a-button type="primary" :loading="evalRunning" @click="runEval">触发对照</a-button>
        </div>
        <a-table
          :data-source="evalRows"
          :columns="evalColumns"
          :pagination="false"
          size="small"
          bordered
          :scroll="{ x: 640 }"
        >
          <template #headerCell="{ column }">
            <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="evalColumns">{{ column.title }}</span>
          </template>
        </a-table>
        <p class="hint">
          所有范式跑<strong>同一批问题</strong>（{{ evalQuestionCount ?? evalLimit }} 题），指标为 mean（Recall@5 / Precision@5 / MRR / nDCG@5），保证对照公平。延迟与 LLM 调用次数见 Live 对照或单 run 详情。
        </p>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<style scoped>
.compare-panel {
  padding: 16px 20px;
  height: 100%;
  overflow-y: auto;
  background: var(--color-bg);
}
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
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 12px;
}
.col-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.col-status {
  font-size: 11px;
  color: var(--color-ink-secondary);
}
.col-status .err {
  color: var(--color-danger);
}
.answer {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--color-border);
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-ink);
  white-space: pre-wrap;
  max-height: 320px;
  overflow-y: auto;
}
.hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
</style>
