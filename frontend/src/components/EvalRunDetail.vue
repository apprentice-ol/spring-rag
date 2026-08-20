<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { ArrowLeftOutlined, DeleteOutlined, QuestionCircleOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { getRun, getMetrics, deleteRun, retryRun, reevaluateItem, type EvalRun, type EvalMetric } from '../api/eval'
import {
  METRIC_KNOWLEDGE,
  metricKey,
  metricLabel,
  fmtScore,
  parseDocIds,
  parseDetail,
  statusText,
  metricStage,
  scoreTone,
  parseAggregate,
  parseParamSnapshot,
  PARADIGMS,
  paradigmLabel,
  CATEGORY_DESC,
  categoryLabel,
} from './evalShared'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'

/** 运行详情独立页（hash 子路由 #/admin/eval/runs/{id}）。 */
const props = defineProps<{ runId: number; datasets?: { id: number; name: string }[] }>()
const emit = defineEmits<{ back: [] }>()

const run = ref<EvalRun | null>(null)
const metrics = ref<EvalMetric[]>([])
const datasetName = computed(() => {
  if (!run.value) return undefined
  return props.datasets?.find((d) => d.id === run.value!.datasetId)?.name || String(run.value.datasetId)
})
const loading = ref(false)
const guideOpen = ref(false) // 指标解读：右侧抽屉

// 指标明细 item 级分页（单 run 全量可达数千题/数十 MB）
const metricPage = ref(1)
const metricPageSize = ref(20)
const itemTotal = ref(0)

// 透视表不用表格内置分页：数据源是服务端拉回的单页数据，内置分页器未绑 total 时
// antdv 会用 data.length（=当前页条数）当总数，把页码锁死在第 1 页且总数显示错误。
// 翻页/改每页条数统一走表格下方绑定服务端 itemTotal 的独立分页器。

const aggregate = computed(() => parseAggregate(run.value))
const params = computed(() => parseParamSnapshot(run.value))

async function load(resetPage = false) {
  loading.value = true
  try {
    if (resetPage) metricPage.value = 1
    run.value = await getRun(props.runId)
    const page = await getMetrics(props.runId, metricPage.value, metricPageSize.value)
    metrics.value = page.records
    itemTotal.value = page.total
  } catch {
    /* ignore */
  } finally {
    loading.value = false
  }
}
async function onPageChange(p: number, size: number) {
  metricPage.value = p
  metricPageSize.value = size
  await load()
}
onMounted(() => load(true))
onUnmounted(() => resizeObserver?.disconnect())
watch(() => props.runId, () => load(true))

const metricNames = computed(() => {
  const names = new Set<string>()
  for (const m of metrics.value) if (m.metricName !== 'error') names.add(m.metricName)
  return Array.from(names)
})

/** 期望召回文档名：优先用 metric 平级字段 expectedDocNames，旧数据（无该列）从 detail 兜底 */
function expectedNamesOf(m: EvalMetric, det: ReturnType<typeof parseDetail>): string[] {
  const direct = parseDocIds(m.expectedDocNames)
  return direct.length ? direct : det?.expectedDocNames || []
}

interface ReevalRow {
  attempt: number
  paradigm: string | null
  rewrite: boolean
  perQuestion: boolean
  remark: string | null
  expectedAnswer: string | null
  generatedAnswer: string | null
  hit: number | null
  retrieved: number
  retrievedNames: string[]
  expectedNames: string[]
  error: string | null
  scores: Record<string, number>
}

interface PivotRow {
  itemId: number
  question: string | null
  paradigm: string | null
  category: string | null
  hit: number | null
  expected: number | null
  retrieved: number
  retrievedNames: string[]
  expectedNames: string[]
  expectedAnswer: string | null
  generatedAnswer: string | null
  reevals: ReevalRow[]
  latestRemark: string | null
  [metric: string]: unknown
}

const pivotRows = computed<PivotRow[]>(() => {
  // 先按 (itemId, attempt>0) 聚合重评历史
  const reevalsByItem = new Map<number, Map<number, ReevalRow>>()
  for (const m of metrics.value) {
    if (!m.attempt || m.attempt === 0) continue
    let perItem = reevalsByItem.get(m.itemId)
    if (!perItem) {
      perItem = new Map()
      reevalsByItem.set(m.itemId, perItem)
    }
    let row = perItem.get(m.attempt)
    if (!row) {
      const det = parseDetail(m.detail)
      const isError = m.metricName === 'error'
      let errMsg: string | null = null
      if (isError) {
        try {
          errMsg = JSON.parse(m.detail || '{}').error || '检索失败'
        } catch {
          errMsg = '检索失败'
        }
      }
      row = {
        attempt: m.attempt,
        paradigm: m.paradigm ?? null,
        rewrite: !!m.rewrite,
        perQuestion: !!m.perQuestion,
        remark: m.remark || null,
        expectedAnswer: m.expectedAnswer ?? null,
        generatedAnswer: m.generatedAnswer ?? null,
        hit: isError ? null : det?.hitCount ?? null,
        retrieved: parseDocIds(m.retrievedDocIds).length,
        retrievedNames: parseDocIds(m.retrievedDocNames),
        expectedNames: expectedNamesOf(m, det),
        error: errMsg,
        scores: {},
      }
      perItem.set(m.attempt, row)
    }
    if (m.metricName !== 'error') row.scores[m.metricName] = m.score
  }
  // 主行只取 attempt=0（旧数据 attempt 为空视为 0）
  const byItem = new Map<number, PivotRow>()
  for (const m of metrics.value) {
    if (m.attempt != null && m.attempt !== 0) continue
    let row = byItem.get(m.itemId)
    if (!row) {
      const det = parseDetail(m.detail)
      const reevals = Array.from((reevalsByItem.get(m.itemId) || new Map()).values()).sort(
        (a, b) => b.attempt - a.attempt,
      )
      row = {
        itemId: m.itemId,
        question: m.question,
        paradigm: m.paradigm ?? null,
        category: m.category ?? null,
        hit: det?.hitCount ?? null,
        expected: det?.expectedCount ?? null,
        retrieved: parseDocIds(m.retrievedDocIds).length,
        retrievedNames: parseDocIds(m.retrievedDocNames),
        expectedNames: expectedNamesOf(m, det),
        expectedAnswer: m.expectedAnswer ?? null,
        generatedAnswer: m.generatedAnswer ?? null,
        reevals,
        latestRemark: reevals[0]?.remark || null,
      }
      byItem.set(m.itemId, row)
    }
    row[m.metricName] = m.score
  }
  return Array.from(byItem.values())
})

/** 该 run 是否存在分类（任一行有 category 才展示分类列与筛选框） */
const hasCategory = computed(() => pivotRows.value.some((r) => r.category))
/** 分类筛选选项：该 run 出现过的分类（去重） */
const categoryOptions = computed(() => {
  const s = new Set<string>()
  for (const r of pivotRows.value) if (r.category) s.add(r.category)
  return Array.from(s)
})
const categoryFilter = ref<string | undefined>(undefined)
/** 分类 → 完整描述（单元格 tooltip 用） */
function categoryDesc(c: string): string {
  return CATEGORY_DESC.find((d) => d.key === c)?.desc ?? c
}

const tableColumns = computed(() => {
  const cols: {
    title: string
    dataIndex?: string
    key: string
    width?: number
    ellipsis?: boolean
    fixed?: 'left' | 'right'
    sorter?: (a: PivotRow, b: PivotRow) => number
  }[] = [{ title: '问题', dataIndex: 'question', key: 'question', width: 280, ellipsis: true, fixed: 'left' }]
  cols.push({ title: '范式', dataIndex: 'paradigm', key: 'paradigm', width: 100 })
  if (hasCategory.value) {
    cols.push({ title: '分类', dataIndex: 'category', key: 'category', width: 110 })
  }
  for (const name of metricNames.value) {
    cols.push({
      title: metricLabel(name),
      dataIndex: name,
      key: name,
      width: 110,
      sorter: (a, b) => (Number(a[name]) || 0) - (Number(b[name]) || 0),
    })
  }
  cols.push({ title: '命中/召回', dataIndex: 'hit', key: 'hit', width: 110 })
  cols.push({ title: '备注', dataIndex: 'latestRemark', key: 'remark', width: 130, ellipsis: true })
  cols.push({ title: '操作', key: 'action', width: 80, fixed: 'right' })
  return cols
})

// 列宽可拖拽:列集合来自上面的 computed(切范式/指标会重建),
// 用 splice 原地同步进可变数组,保持数组身份不变以配合 v-resize 指令。
const pivotColumns = useResizableColumns(tableColumns.value)
watch(tableColumns, (cols) => {
  pivotColumns.value.splice(0, pivotColumns.value.length, ...cols)
})

const searchKeyword = ref('')
const reevalOnly = ref(false)
const badcaseOnly = ref(false)
const badcaseMetric = ref<string>('')
const badcaseThreshold = ref(0.5)

watch(metricNames, (ns) => {
  if (!badcaseMetric.value && ns.length) {
    badcaseMetric.value = ns.find((n) => n === 'precision_at_5') || ns[0]
  }
})

const filteredRows = computed(() => {
  let rows = pivotRows.value
  const kw = searchKeyword.value.trim().toLowerCase()
  if (kw) rows = rows.filter((r) => String(r.question || '').toLowerCase().includes(kw))
  if (categoryFilter.value) rows = rows.filter((r) => r.category === categoryFilter.value)
  if (reevalOnly.value) rows = rows.filter((r) => r.reevals && r.reevals.length > 0)
  if (badcaseOnly.value && badcaseMetric.value) {
    const th = badcaseThreshold.value
    rows = rows.filter((r) => {
      const v = Number(r[badcaseMetric.value])
      return Number.isFinite(v) && v < th
    })
  }
  return rows
})

const guides = computed(() => {
  if (!aggregate.value) return []
  return Object.entries(aggregate.value)
    .filter(([, m]) => m.count > 0)
    .map(([name, m]) => {
      const know = METRIC_KNOWLEDGE[metricKey(name)]
      return {
        name,
        mean: m.mean,
        stage: know?.stage ?? '',
        dir: know?.dir ?? '',
        desc: know?.desc ?? '',
        judge: know?.judge(m.mean) ?? '',
      }
    })
})

function goBack() {
  emit('back')
}

function confirmDelete() {
  Modal.confirm({
    title: '删除运行',
    content: `确定删除运行 #${props.runId}？含全量逐题指标，不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteRun(props.runId)
        message.success('已删除运行')
        emit('back')
      } catch (e: unknown) {
        message.error((e as ErrResp)?.response?.data?.message || '删除失败')
      }
    },
  })
}

async function retry() {
  try {
    const { runId } = await retryRun(props.runId)
    message.success(`已重新触发运行 #${runId}`)
    window.location.hash = '#/admin/eval/runs/' + runId
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '重试失败')
  }
}

// ── 单条重评（保留历史，新增 attempt；可选启用改写/per-question、可填备注）──
const reevalModalOpen = ref(false)
const reevalItem = ref<PivotRow | null>(null)
const reevalRewrite = ref(false)
const reevalPerQuestion = ref(false)
const reevalRemark = ref('')
const reevalParadigm = ref('')
const reevaluating = ref(false)

function openReeval(row: PivotRow) {
  reevalItem.value = row
  reevalRewrite.value = false
  // 默认带出原 run 的 per-question 设置，可改（提交时始终传明确值，覆盖原快照）
  reevalPerQuestion.value = !!params.value?.perQuestion
  reevalRemark.value = ''
  reevalParadigm.value = ''
  reevalModalOpen.value = true
}

async function submitReeval() {
  if (!reevalItem.value) return
  reevaluating.value = true
  try {
    const { attempt } = await reevaluateItem(props.runId, reevalItem.value.itemId, {
      rewriteEnabled: reevalRewrite.value,
      remark: reevalRemark.value.trim() || undefined,
      paradigm: reevalParadigm.value || undefined,
      perQuestion: reevalPerQuestion.value,
    })
    message.success(`重评完成（attempt #${attempt}）`)
    reevalModalOpen.value = false
    await load()
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '重评失败')
  } finally {
    reevaluating.value = false
  }
}

interface ErrResp {
  response?: { data?: { message?: string } }
}

// ── 表格高度自适应：滚动表格数据时，表头 + 上方参数卡/聚合卡固定不动 ──
const tableWrap = ref<HTMLElement>()
const tableBodyHeight = ref<number | undefined>(undefined)
let resizeObserver: ResizeObserver | undefined

function measure() {
  const el = tableWrap.value
  if (!el) return
  const wrapH = el.getBoundingClientRect().height
  const headH = el.querySelector('.ant-table-thead')?.getBoundingClientRect().height ?? 47
  // 分页条也占容器高度（含上下留白的 margin 补偿），不减会溢出裁掉分页
  const pageH = el.querySelector('.ant-pagination')?.getBoundingClientRect().height ?? 48
  tableBodyHeight.value = Math.max(160, Math.floor(wrapH - headH - pageH - 24))
}

watch(tableWrap, (el) => {
  resizeObserver?.disconnect()
  if (el) {
    nextTick(measure)
    resizeObserver = new ResizeObserver(measure)
    resizeObserver.observe(el)
  }
})

function durMin(): string {
  if (!run.value?.startedAt || !run.value?.finishedAt) return '-'
  const a = new Date(run.value.startedAt).getTime()
  const b = new Date(run.value.finishedAt).getTime()
  if (!Number.isFinite(a) || !Number.isFinite(b) || b < a) return '-'
  return ((b - a) / 60000).toFixed(1) + ' min'
}
</script>

<template>
  <div class="run-detail">
    <!-- 页头：返回 + 运行概要 + 操作 -->
    <div class="detail-header">
      <div class="header-main">
        <a-button type="text" class="back-btn" @click="goBack">
          <template #icon><ArrowLeftOutlined /></template>返回运行记录
        </a-button>
        <template v-if="run">
          <span class="run-title">运行 #{{ run.id }}</span>
          <a-tag :color="run.status === 'DONE' ? 'green' : run.status === 'FAILED' ? 'red' : 'processing'">
            {{ statusText(run.status) }}
          </a-tag>
          <a-tag v-if="run.paradigm" color="purple">{{ paradigmLabel(run.paradigm) }}</a-tag>
        </template>
        <span class="header-right">
          <a-button class="guide-btn" @click="guideOpen = true">
            <template #icon><QuestionCircleOutlined /></template>指标解读
          </a-button>
          <a-button v-if="run?.status === 'FAILED'" type="primary" @click="retry">
            <template #icon><ReloadOutlined /></template>重试
          </a-button>
          <a-button danger @click="confirmDelete">
            <template #icon><DeleteOutlined /></template>删除运行
          </a-button>
        </span>
      </div>
      <div v-if="run" class="run-meta">
        {{ datasetName }} · {{ run.done }}/{{ run.total ?? '-' }} 题 · 耗时 {{ durMin() }}
      </div>
    </div>

    <a-spin :spinning="loading">
      <!-- 检索参数卡（该次运行的 topK / 阈值 / 召回预算 等） -->
      <div v-if="params" class="param-card">
        <span class="param-title">检索参数</span>
        <span class="p-item"><span class="p-label">topK</span><b>{{ params.topK ?? '-' }}</b></span>
        <span class="p-item"><span class="p-label">相似度阈值</span><b>{{ params.threshold ?? '-' }}</b></span>
        <span class="p-item"><span class="p-label">召回预算</span><b>{{ params.recallBudget ?? '-' }}</b></span>
        <span class="p-item"><span class="p-label">候选上限</span><b>{{ params.candidateLimit ?? '-' }}</b></span>
        <span class="p-item"><span class="p-label">上下文 topK</span><b>{{ params.contextTopK ?? '-' }}</b></span>
        <span class="p-item">
          <span class="p-label">查询改写</span>
          <b :class="params.rewrite ? 'on' : 'off'">{{ params.rewrite ? '开' : '关' }}</b>
        </span>
        <span class="p-item">
          <span class="p-label">答案评测</span>
          <b :class="params.answerEval ? 'on' : 'off'">{{ params.answerEval ? '开' : '关' }}</b>
        </span>
        <span class="p-item">
          <span class="p-label">限定期望文档</span>
          <b :class="params.perQuestion ? 'on' : 'off'">{{ params.perQuestion ? '开' : '关' }}</b>
        </span>
        <!-- 抽样说明：范式/数量决策信息（如"抽样数量 ≥ 范围内条目数，已全量评测该范式"） -->
        <span v-if="params.note" class="p-item note">
          <span class="p-label">抽样说明</span>
          <b>{{ params.note }}</b>
        </span>
      </div>

      <!-- 聚合指标卡（与概览页 KPI 卡同风格：白卡 + 阶段徽标 + 分档色数字） -->
      <div v-if="aggregate" class="agg-grid">
        <div
          v-for="(m, name) in aggregate"
          :key="name"
          class="agg-card"
          :class="'tone-' + scoreTone(m.mean)"
        >
          <div class="agg-head">
            <span class="agg-name">{{ metricLabel(String(name)) }}</span>
            <span class="agg-stage" :class="metricStage(String(name)).cls">
              {{ metricStage(String(name)).label }}
            </span>
          </div>
          <div class="agg-mean">{{ fmtScore(m.mean) }}</div>
          <div class="agg-bar"><span :style="{ width: Math.max(0, Math.min(1, m.mean)) * 100 + '%' }"></span></div>
          <div class="agg-detail">中位 {{ fmtScore(m.median) }} · min {{ fmtScore(m.min) }} · max {{ fmtScore(m.max) }}</div>
        </div>
      </div>
      <div v-else-if="run && run.status === 'DONE'" class="empty">无聚合指标数据</div>

      <!-- 逐题明细表格卡：工具栏筛选 + 表格内部滚动（表头与上方参数卡/聚合卡固定） -->
      <div v-if="pivotRows.length" class="table-card">
        <div class="table-toolbar">
          <div class="toolbar-left">
            <a-input-search v-model:value="searchKeyword" placeholder="搜索问题关键词" allow-clear style="width: 220px" />
            <a-select
              v-if="hasCategory"
              v-model:value="categoryFilter"
              placeholder="数据分类"
              allow-clear
              style="width: 140px"
            >
              <a-select-option v-for="c in categoryOptions" :key="c" :value="c">{{ categoryLabel(c) }}</a-select-option>
            </a-select>
            <a-checkbox v-model:checked="reevalOnly">仅看重评过</a-checkbox>
            <a-checkbox v-model:checked="badcaseOnly">低分筛选</a-checkbox>
            <template v-if="badcaseOnly">
              <a-select v-model:value="badcaseMetric" style="width: 160px">
                <a-select-option v-for="n in metricNames" :key="n" :value="n">{{ metricLabel(n) }}</a-select-option>
              </a-select>
              <span class="op">&lt;</span>
              <a-input-number v-model:value="badcaseThreshold" :min="0" :max="1" :step="0.1" style="width: 90px" />
            </template>
          </div>
          <span class="toolbar-hint">当前页 {{ filteredRows.length }} / {{ pivotRows.length }} · 共 {{ itemTotal }} 题</span>
        </div>

        <div ref="tableWrap" class="table-wrap">
          <a-table
            :columns="pivotColumns"
            :data-source="filteredRows"
            :pagination="false"
            size="middle"
            row-key="itemId"
            :scroll="{ x: 1320, y: tableBodyHeight }"
            class="metric-table"
          >
            <template #headerCell="{ column }">
              <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="pivotColumns">{{ column.title }}</span>
            </template>
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'question'">
                <a-tooltip :title="record.question"><span class="q-cell">{{ record.question }}</span></a-tooltip>
              </template>
              <template v-else-if="column.key === 'paradigm'">
                <a-tag v-if="record.paradigm" color="purple">{{ paradigmLabel(record.paradigm) }}</a-tag>
                <span v-else class="muted">-</span>
              </template>
              <template v-else-if="column.key === 'category'">
                <a-tooltip v-if="record.category" :title="categoryDesc(record.category)">
                  <a-tag>{{ categoryLabel(record.category) }}</a-tag>
                </a-tooltip>
                <span v-else class="muted">-</span>
              </template>
              <template v-else-if="column.key === 'hit'">{{ record.hit ?? '-' }} / {{ record.retrieved }}</template>
              <template v-else-if="column.key === 'remark'">
                <a-tooltip v-if="record.latestRemark" :title="record.latestRemark">
                  <span class="remark-chip">{{ record.latestRemark }}</span>
                </a-tooltip>
                <span v-else class="muted">—</span>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-button type="link" size="small" @click="openReeval(record)">重评</a-button>
              </template>
              <template v-else-if="metricNames.includes(column.dataIndex)">
                <span :class="'cell-tone-' + scoreTone(Number(record[column.dataIndex]))">{{ fmtScore(record[column.dataIndex]) }}</span>
              </template>
            </template>
            <template #expandedRowRender="{ record }">
              <div class="expand">
                <div class="exp-summary">
                  命中 {{ record.hit ?? '-' }} / 期望 {{ record.expected ?? '-' }} · 实际召回 {{ record.retrieved }} 篇
                </div>
                <!-- 标准答案置顶：答案评测对照的基准 -->
                <div v-if="record.expectedAnswer" class="exp-row ans-row">
                  <span class="exp-label">标准答案</span>
                  <span class="ans-text">{{ record.expectedAnswer }}</span>
                </div>
                <!-- 系统回答：answerEval 主批次的生成结果（重评每次的答案在下方重评历史里） -->
                <div v-if="record.generatedAnswer" class="exp-row ans-row">
                  <span class="exp-label">系统回答</span>
                  <span class="ans-text">{{ record.generatedAnswer }}</span>
                </div>
                <!-- 期望召回：命中=绿、未召回=红，badcase 一眼定位 -->
                <div class="exp-row">
                  <span class="exp-label">期望召回</span>
                  <template v-if="record.expectedNames?.length">
                    <span
                      v-for="(n, i) in record.expectedNames"
                      :key="'exp-' + i + '-' + n"
                      class="doc-chip"
                      :class="record.retrievedNames?.includes(n) ? 'hit' : 'miss'"
                    >
                      {{ n }}<i class="chip-mark">{{ record.retrievedNames?.includes(n) ? '✓' : '✗' }}</i>
                    </span>
                  </template>
                  <span v-else class="muted">（无）</span>
                </div>
                <!-- 实际召回：按排名序号排列，期望内=蓝、多召回=灰 -->
                <div class="exp-row">
                  <span class="exp-label">实际召回</span>
                  <template v-if="record.retrievedNames?.length">
                    <span
                      v-for="(n, i) in record.retrievedNames"
                      :key="'ret-' + i + '-' + n"
                      class="doc-chip"
                      :class="record.expectedNames?.includes(n) ? 'hit-soft' : 'extra'"
                    >
                      <i class="rank">{{ i + 1 }}</i>{{ n }}
                    </span>
                  </template>
                  <span v-else class="muted">（无）</span>
                </div>
                <div v-if="record.reevals && record.reevals.length" class="reevals">
                  <div class="reevals-title">重评历史（{{ record.reevals.length }} 次，保留对比）</div>
                  <div v-for="r in record.reevals" :key="r.attempt" class="reeval-item">
                    <span class="rv-attempt">#{{ r.attempt }}</span>
                    <a-tag v-if="r.paradigm" color="blue" class="rv-tag">{{ paradigmLabel(r.paradigm) }}</a-tag>
                    <a-tag :color="r.rewrite ? 'green' : 'default'" class="rv-tag">{{ r.rewrite ? '含改写' : '裸检索' }}</a-tag>
                    <a-tag v-if="r.perQuestion" color="orange" class="rv-tag">仅期望文档</a-tag>
                    <template v-if="r.error">
                      <span class="rv-error">检索失败：{{ r.error }}</span>
                    </template>
                    <template v-else>
                      <span class="rv-hit">命中 {{ r.hit ?? '-' }} / {{ r.retrieved }}</span>
                      <span v-for="k in Object.keys(r.scores)" :key="k" class="rv-score">
                        {{ metricLabel(k) }} {{ fmtScore(r.scores[k]) }}
                      </span>
                      <span v-if="r.expectedAnswer" class="rv-ans">标准答案：{{ r.expectedAnswer }}</span>
                      <span v-if="r.generatedAnswer" class="rv-ans">系统回答：{{ r.generatedAnswer }}</span>
                      <span v-if="r.expectedNames.length" class="rv-exp">期望：{{ r.expectedNames.join('，') }}</span>
                      <span v-if="r.retrievedNames.length" class="rv-docs">实际召回：{{ r.retrievedNames.join('，') }}</span>
                    </template>
                    <div v-if="r.remark" class="rv-remark">📝 {{ r.remark }}</div>
                  </div>
                </div>
              </div>
            </template>
          </a-table>
          <!-- 指标明细 item 级分页（total 按 item 计，每页含这些 item 的全部指标行）；翻页走服务端 -->
          <div class="metrics-pagination">
            <a-pagination
              :current="metricPage"
              :page-size="metricPageSize"
              :total="itemTotal"
              show-size-changer
              :page-size-options="['10', '20', '50', '100']"
              size="small"
              show-quick-jumper
              :show-total="(t: number) => `共 ${t} 题`"
              @change="onPageChange"
            />
          </div>
        </div>
      </div>
    </a-spin>

    <!-- 单条重评弹窗 -->
    <a-modal
      :open="reevalModalOpen"
      title="单条重评"
      ok-text="开始重评"
      cancel-text="取消"
      :confirm-loading="reevaluating"
      @update:open="(v: boolean) => (reevalModalOpen = v)"
      @ok="submitReeval"
    >
      <div v-if="reevalItem" class="reeval-q">问题：{{ reevalItem.question }}</div>
      <a-form layout="vertical">
        <a-form-item label="agent 范式">
          <a-select v-model:value="reevalParadigm" placeholder="沿用原 run 范式">
            <a-select-option :value="''">沿用原 run（{{ paradigmLabel(run?.paradigm) }}）</a-select-option>
            <a-select-option v-for="p in PARADIGMS" :key="p.value" :value="p.value">{{ p.label }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="reevalRewrite">启用查询改写（LLM 改写后再检索）</a-checkbox>
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="reevalPerQuestion">仅检索期望文档（限定在该题期望文档内，排除语料噪声的上限对照）</a-checkbox>
        </a-form-item>
        <a-form-item label="备注（可选）">
          <a-textarea v-model:value="reevalRemark" :rows="3" placeholder="如：测试改写后能否召回期望文档" />
        </a-form-item>
        <div class="reeval-tip">重评会重新检索并打分；条目有标准答案时，同时生成系统回答并与标准答案对照展示。</div>
      </a-form>
    </a-modal>

    <!-- 指标解读：右侧抽屉 -->
    <a-drawer :open="guideOpen" title="指标解读" placement="right" :width="440" @update:open="(v: boolean) => (guideOpen = v)">
      <div class="guide-tip-top">衡量检索管线：召回 → 去重 → RRF 融合 → Rerank（不衡量 LLM 回答质量）</div>
      <div v-for="g in guides" :key="g.name" class="guide-row">
        <div class="g-head">
          <span class="g-name">{{ metricLabel(g.name) }}</span>
          <span class="g-stage">{{ g.stage }}</span>
          <span class="g-dir">{{ g.dir }}</span>
          <span class="g-value">当前 {{ fmtScore(g.mean) }}</span>
        </div>
        <div class="g-desc">{{ g.desc }}</div>
        <div class="g-judge">{{ g.judge }}</div>
      </div>
      <div class="g-tip">
        指标没有「合格线」—— 同一套参数下对比不同数据集 / 不同参数跑批的差异，才是调优的正确姿势。
        中位 ≈ max 说明大多数题表现一致，个别 min 值点开上方明细定位 badcase。
      </div>
    </a-drawer>
  </div>
</template>

<style scoped>
.metrics-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 8px 0 4px;
}
/* ── 整页骨架：不滚动，表格卡内部滚动（表头 + 参数卡 + 聚合卡固定） ── */
.run-detail {
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
  overflow: hidden;
}
/* a-spin 内部走 flex 列布局，让表格卡吃掉剩余高度 */
.run-detail :deep(.ant-spin-nested-loading),
.run-detail :deep(.ant-spin-container) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.run-detail :deep(.ant-spin-container) {
  gap: 14px;
}
/* 参数卡 / 聚合卡固定不压缩；表格卡（全局类）吃剩余空间，表体在 table-wrap 内滚 */
.run-detail .param-card,
.run-detail .agg-grid,
.run-detail .empty {
  flex-shrink: 0;
}
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 分页贴表格底部（配合 measure() 的高度扣算） */
.run-detail :deep(.ant-pagination) {
  margin: 10px 16px 12px 8px;
}

/* ── 页头 ── */
.detail-header {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}
.header-main {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.back-btn {
  color: var(--color-primary);
  font-size: 14px;
  margin-left: -7px; /* 补偿 antd 文字按钮内边距，与标题视觉对齐 */
}
.run-title {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: 1.3;
  color: var(--color-ink);
}
.run-meta {
  font-size: 13px;
  color: var(--color-ink-tertiary);
}
.header-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.guide-btn {
  color: var(--color-primary);
}

/* ── 聚合指标卡（对齐概览页 kpi-card：白卡 + 阶段徽标 + 分档色数字 + 细进度条） ── */
.agg-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.agg-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 14px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.agg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.agg-name {
  font-size: 12px;
  color: var(--color-ink-secondary);
  font-weight: 500;
}
/* 阶段徽标：检索=蓝、排序=琥珀（与概览页 KPI 卡一致） */
.agg-stage {
  font-size: 11px;
  line-height: 1;
  padding: 3px 6px;
  border-radius: var(--radius-sm);
  font-weight: 500;
}
.agg-stage.stage-ret {
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.agg-stage.stage-sort {
  color: #b07810;
  background: var(--color-signal-bg);
}
.agg-stage.stage-answer {
  color: var(--color-success);
  background: var(--color-success-bg);
}
.agg-mean {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: 1.3;
  color: var(--color-ink);
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
}
/* 分档着色只落在数字与进度条上（替代旧版彩色顶边） */
.agg-card.tone-good .agg-mean {
  color: var(--color-success);
}
.agg-card.tone-mid .agg-mean {
  color: #b07810;
}
.agg-card.tone-bad .agg-mean {
  color: var(--color-danger);
}
.agg-bar {
  height: 4px;
  border-radius: 2px;
  background: var(--color-surface-secondary);
  margin: 6px 0 4px;
  overflow: hidden;
}
.agg-bar span {
  display: block;
  height: 100%;
  border-radius: 2px;
  transition: width 0.25s ease;
}
.agg-card.tone-good .agg-bar span {
  background: var(--color-success);
}
.agg-card.tone-mid .agg-bar span {
  background: var(--color-signal);
}
.agg-card.tone-bad .agg-bar span {
  background: var(--color-danger);
}
.agg-detail {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
}

/* ── 检索参数卡 ── */
.param-card {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
}
.param-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-ink-secondary);
  padding-right: 14px;
  border-right: 1px solid var(--color-border-light);
}
.p-item {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}
.p-label {
  font-size: 12px;
  color: var(--color-ink-tertiary);
  white-space: nowrap;
}
.p-item b {
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
  font-weight: 600;
  color: var(--color-ink);
}
.p-item b.on {
  color: var(--color-success);
}
.p-item b.off {
  color: var(--color-ink-tertiary);
  font-weight: 400;
}
.p-item.note {
  flex-basis: 100%;
}
.p-item.note b {
  font-weight: 400;
  color: var(--color-ink-secondary);
  line-height: 1.5;
}

.op {
  color: var(--color-ink-tertiary);
}

/* ── 逐题明细表 ── */
.metric-table :deep(table) {
  table-layout: fixed;
}
.q-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-ink);
}
.cell-tone-good {
  color: var(--color-success);
  font-weight: 600;
}
.cell-tone-bad {
  color: var(--color-danger);
}
.muted {
  color: var(--color-ink-tertiary);
}
.remark-chip {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 1px 8px;
  border-radius: var(--radius-lg);
}

/* ── 展开行：期望 vs 实际召回 chips 对照 ── */
.expand {
  font-size: 12px;
  color: var(--color-ink-secondary);
  padding: 4px 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.exp-summary {
  color: var(--color-ink);
  font-weight: 500;
}
.exp-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex-wrap: wrap;
}
.exp-label {
  flex-shrink: 0;
  color: var(--color-ink-tertiary);
  line-height: 22px;
}
/* 答案对照：标准答案 / 系统回答 */
.ans-row {
  align-items: flex-start;
}
.ans-text {
  flex: 1;
  min-width: 0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  color: var(--color-ink);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  padding: 6px 10px;
  font-size: 12px;
}
.doc-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 280px;
  padding: 1px 8px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  line-height: 20px;
  background: var(--color-surface-secondary);
  color: var(--color-ink-secondary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
/* 期望行：命中=绿 / 未召回=红 */
.doc-chip.hit {
  background: var(--color-success-bg);
  color: var(--color-success);
  font-weight: 500;
}
.doc-chip.miss {
  background: var(--color-danger-bg);
  color: var(--color-danger);
  font-weight: 500;
}
/* 实际行：期望内=蓝 / 多召回=灰 */
.doc-chip.hit-soft {
  background: var(--color-primary-light);
  color: var(--color-primary);
}
.chip-mark {
  font-style: normal;
  font-family: var(--font-display);
}
.rank {
  font-style: normal;
  font-family: var(--font-display);
  font-size: 11px;
  opacity: 0.65;
}

/* ── 重评历史（展开行内） ── */
.reevals {
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px dashed var(--color-border-light);
}
.reevals-title {
  font-size: 12px;
  color: var(--color-primary);
  margin-bottom: 6px;
  font-weight: 600;
}
.reeval-item {
  font-size: 12px;
  color: var(--color-ink-secondary);
  padding: 4px 0;
  line-height: 1.8;
}
.rv-attempt {
  display: inline-block;
  font-weight: 600;
  color: var(--color-ink);
  margin-right: 6px;
}
.rv-tag {
  margin-right: 6px;
}
.rv-hit {
  margin-right: 8px;
  color: var(--color-ink);
}
.rv-score {
  margin-right: 8px;
}
.rv-ans {
  display: block;
  color: var(--color-ink);
  white-space: pre-wrap;
  word-break: break-word;
  margin: 2px 0;
}
.rv-exp {
  display: block;
  color: var(--color-primary);
}
.rv-docs {
  display: block;
  color: var(--color-ink-tertiary);
}
.rv-error {
  color: var(--color-danger);
}
.rv-remark {
  color: var(--color-ink);
  background: var(--color-signal-bg);
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  display: inline-block;
  margin-top: 2px;
}

/* ── 重评弹窗问题预览 ── */
.reeval-q {
  font-size: 13px;
  color: var(--color-ink);
  background: var(--color-surface-secondary);
  padding: 8px 12px;
  border-radius: var(--radius-md);
  margin-bottom: 12px;
  line-height: 1.6;
}
.reeval-tip {
  font-size: 12px;
  color: var(--color-ink-tertiary);
  line-height: 1.6;
}

/* ── 指标解读抽屉 ── */
.guide-tip-top {
  font-size: 12px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  padding: 8px 12px;
  border-radius: var(--radius-md);
  margin-bottom: 12px;
}
.guide-row {
  border-top: 1px solid var(--color-border-light);
  padding-top: 10px;
  margin-top: 10px;
}
.guide-row:first-of-type {
  border-top: none;
  margin-top: 0;
}
.g-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 4px;
}
.g-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--color-ink);
}
.g-stage,
.g-dir {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--radius-lg);
  background: var(--color-surface-secondary);
  color: var(--color-ink-secondary);
}
.g-value {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-primary);
  font-weight: 600;
}
.g-desc,
.g-judge {
  font-size: 12px;
  color: var(--color-ink-secondary);
  line-height: 1.7;
  margin-top: 2px;
}
.g-judge {
  color: var(--color-ink);
}
.g-tip {
  border-top: 1px dashed var(--color-border-light);
  padding-top: 10px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
  line-height: 1.7;
}

.empty {
  text-align: center;
  padding: 24px;
  color: var(--color-ink-tertiary);
  font-size: 13px;
}
</style>
