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
  isRetrievalMetric,
  scoreTone,
  parseAggregate,
  parseParamSnapshot,
  PARADIGMS,
  paradigmLabel,
  CATEGORY_DESC,
  categoryLabel,
} from './evalShared'

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

const aggregate = computed(() => parseAggregate(run.value))
const params = computed(() => parseParamSnapshot(run.value))

async function load() {
  loading.value = true
  try {
    run.value = await getRun(props.runId)
    metrics.value = await getMetrics(props.runId)
  } catch {
    /* ignore */
  } finally {
    loading.value = false
  }
}
onMounted(load)
onUnmounted(() => resizeObserver?.disconnect())
watch(() => props.runId, load)

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
  remark: string | null
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
        remark: m.remark || null,
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

// ── 单条重评（保留历史，新增 attempt；可选启用改写、可填备注）──
const reevalModalOpen = ref(false)
const reevalItem = ref<PivotRow | null>(null)
const reevalRewrite = ref(false)
const reevalRemark = ref('')
const reevalParadigm = ref('')
const reevaluating = ref(false)

function openReeval(row: PivotRow) {
  reevalItem.value = row
  reevalRewrite.value = false
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

// ── 表格高度自适应：滚动表格数据时，表头 + 上方参数条/聚合卡固定不动 ──
const tableWrap = ref<HTMLElement>()
const tableBodyHeight = ref<number | undefined>(undefined)
let resizeObserver: ResizeObserver | undefined

function measure() {
  const el = tableWrap.value
  if (!el) return
  const wrapH = el.getBoundingClientRect().height
  const headH = el.querySelector('.ant-table-thead')?.getBoundingClientRect().height ?? 47
  tableBodyHeight.value = Math.max(160, Math.floor(wrapH - headH))
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
    <!-- 顶部：返回 + 概览 + 解读入口 + 删除 -->
    <div class="detail-header">
      <a-button type="text" class="back-btn" @click="goBack">
        <template #icon><ArrowLeftOutlined /></template>返回运行记录
      </a-button>
      <template v-if="run">
        <span class="run-title">运行 #{{ run.id }}</span>
        <a-tag :color="run.status === 'DONE' ? 'green' : run.status === 'FAILED' ? 'red' : 'processing'">
          {{ statusText(run.status) }}
        </a-tag>
        <a-tag v-if="run.paradigm" color="teal">{{ paradigmLabel(run.paradigm) }}</a-tag>
        <span class="run-meta">{{ datasetName }} · {{ run.done }}/{{ run.total ?? '-' }} 题 · {{ durMin() }}</span>
      </template>
      <span class="header-right">
        <a-button class="guide-btn" @click="guideOpen = true">
          <template #icon><QuestionCircleOutlined /></template>指标解读
        </a-button>
        <a-button v-if="run?.status === 'FAILED'" type="primary" size="small" @click="retry">
          <template #icon><ReloadOutlined /></template>重试
        </a-button>
        <a-button danger size="small" @click="confirmDelete">
          <template #icon><DeleteOutlined /></template>删除运行
        </a-button>
      </span>
    </div>

    <a-spin :spinning="loading">
      <!-- 本次检索参数（该次运行的 topK / 阈值 / 召回预算 等） -->
      <div v-if="params" class="param-bar">
        <span class="param-title">检索参数</span>
        <span class="param-item">topK <b>{{ params.topK ?? '-' }}</b></span>
        <span class="param-item">相似度阈值 <b>{{ params.threshold ?? '-' }}</b></span>
        <span class="param-item">召回预算 <b>{{ params.recallBudget ?? '-' }}</b></span>
        <span class="param-item">候选上限 <b>{{ params.candidateLimit ?? '-' }}</b></span>
        <span class="param-item">上下文 topK <b>{{ params.contextTopK ?? '-' }}</b></span>
        <span class="param-item">查询改写 <b :class="params.rewrite ? 'on' : 'off'">{{ params.rewrite ? '开' : '关' }}</b></span>
      </div>
      <!-- 聚合卡片（openobserve 式：白底 + 分档色 + 大数字 + 中文标签） -->
      <div v-if="aggregate" class="agg-grid">
        <div
          v-for="(m, name) in aggregate"
          :key="name"
          class="agg-card"
          :class="'tone-' + scoreTone(m.mean)"
        >
          <div class="agg-head">
            <span class="agg-name">{{ metricLabel(String(name)) }}</span>
            <span class="agg-stage" :class="isRetrievalMetric(String(name)) ? 'stage-ret' : 'stage-sort'">
              {{ isRetrievalMetric(String(name)) ? '检索' : '排序' }}
            </span>
          </div>
          <div class="agg-mean">{{ fmtScore(m.mean) }}</div>
          <div class="agg-bar"><span :style="{ width: Math.max(0, Math.min(1, m.mean)) * 100 + '%' }"></span></div>
          <div class="agg-detail">中位 {{ fmtScore(m.median) }} · min {{ fmtScore(m.min) }} · max {{ fmtScore(m.max) }}</div>
        </div>
      </div>
      <div v-else-if="run && run.status === 'DONE'" class="empty">无聚合指标数据</div>

      <!-- 工具栏 -->
      <div v-if="pivotRows.length" class="toolbar">
        <a-input-search v-model:value="searchKeyword" placeholder="搜索问题关键词" allow-clear style="width: 240px" />
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
        <span class="filter-count">显示 {{ filteredRows.length }} / {{ pivotRows.length }} 条</span>
      </div>

      <!-- 逐题明细（列头中文 + 排序 + 行展开）；表格内部滚动，表头 + 上方参数条/聚合卡固定 -->
      <div v-if="pivotRows.length" ref="tableWrap" class="table-wrap">
        <a-table
          :columns="tableColumns"
          :data-source="filteredRows"
          :pagination="{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'], showTotal: (t: number) => `共 ${t} 条` }"
          size="middle"
          row-key="itemId"
          :scroll="{ x: 1320, y: tableBodyHeight }"
          class="metric-table"
        >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'question'">
            <a-tooltip :title="record.question"><span class="q-cell">{{ record.question }}</span></a-tooltip>
          </template>
          <template v-else-if="column.key === 'paradigm'">
            <a-tag v-if="record.paradigm" color="teal">{{ paradigmLabel(record.paradigm) }}</a-tag>
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
            <div>命中 {{ record.hit ?? '-' }} / 期望 {{ record.expected ?? '-' }} · 召回 {{ record.retrieved }} 篇</div>
            <div class="docs">期望召回文档：{{ record.expectedNames?.length ? record.expectedNames.join('，') : '（无）' }}</div>
            <div class="docs">实际召回文档：{{ record.retrievedNames?.length ? record.retrievedNames.join('，') : '（无）' }}</div>
            <div v-if="record.reevals && record.reevals.length" class="reevals">
              <div class="reevals-title">重评历史（{{ record.reevals.length }} 次，保留对比）</div>
              <div v-for="r in record.reevals" :key="r.attempt" class="reeval-item">
                <span class="rv-attempt">#{{ r.attempt }}</span>
                <a-tag v-if="r.paradigm" color="blue" class="rv-tag">{{ paradigmLabel(r.paradigm) }}</a-tag>
                <a-tag :color="r.rewrite ? 'green' : 'default'" class="rv-tag">{{ r.rewrite ? '含改写' : '裸检索' }}</a-tag>
                <template v-if="r.error">
                  <span class="rv-error">检索失败：{{ r.error }}</span>
                </template>
                <template v-else>
                  <span class="rv-hit">命中 {{ r.hit ?? '-' }} / {{ r.retrieved }}</span>
                  <span v-for="k in Object.keys(r.scores)" :key="k" class="rv-score">
                    {{ metricLabel(k) }} {{ fmtScore(r.scores[k]) }}
                  </span>
                  <span v-if="r.expectedNames.length" class="rv-exp">期望：{{ r.expectedNames.join('，') }}</span>
                  <span v-if="r.retrievedNames.length" class="rv-docs">实际召回：{{ r.retrievedNames.join('，') }}</span>
                </template>
                <div v-if="r.remark" class="rv-remark">📝 {{ r.remark }}</div>
              </div>
            </div>
          </div>
        </template>
        </a-table>
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
        <a-form-item label="备注（可选）">
          <a-textarea v-model:value="reevalRemark" :rows="3" placeholder="如：测试改写后能否召回期望文档" />
        </a-form-item>
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
.run-detail {
  padding: 20px 24px;
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  overflow: hidden; /* 整页不滚：表格内部滚动，表头 + 上方参数条/聚合卡固定 */
}
/* a-spin 内部走 flex 列布局，让表格区吃掉剩余高度 */
.run-detail :deep(.ant-spin-nested-loading),
.run-detail :deep(.ant-spin-container) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* 参数条 / 聚合卡 / 工具栏固定不压缩 */
.run-detail .param-bar,
.run-detail .agg-grid,
.run-detail .toolbar,
.run-detail .empty {
  flex-shrink: 0;
}
/* 表格区：占剩余空间，表体内部滚动 */
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* 顶部 */
.detail-header {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}
.back-btn {
  color: #1890ff;
  font-size: 14px;
}
.run-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}
.run-meta {
  font-size: 13px;
  color: #999;
}
.header-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}
.guide-btn {
  color: #1890ff;
}

/* 聚合卡片：openobserve 浅色风 */
.agg-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.agg-card {
  background: #ffffff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 14px 16px;
  border-top: 3px solid #d9d9d9;
}
.agg-card.tone-good {
  border-top-color: #52c41a;
}
.agg-card.tone-mid {
  border-top-color: #faad14;
}
.agg-card.tone-bad {
  border-top-color: #f5222d;
}
.agg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.agg-name {
  font-size: 13px;
  color: #666;
  font-weight: 500;
}
.agg-stage {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 10px;
}
.agg-stage.stage-ret {
  background: #e6f7ff;
  color: #1890ff;
}
.agg-stage.stage-sort {
  background: #fff7e6;
  color: #fa8c16;
}
.agg-mean {
  font-size: 26px;
  font-weight: 700;
  color: #333;
  line-height: 1.2;
}
.agg-card.tone-good .agg-mean {
  color: #52c41a;
}
.agg-card.tone-mid .agg-mean {
  color: #faad14;
}
.agg-card.tone-bad .agg-mean {
  color: #f5222d;
}
.agg-bar {
  height: 4px;
  border-radius: 2px;
  background: #f5f5f5;
  margin: 8px 0 6px;
  overflow: hidden;
}
.agg-bar span {
  display: block;
  height: 100%;
  border-radius: 2px;
}
.agg-card.tone-good .agg-bar span {
  background: #52c41a;
}
.agg-card.tone-mid .agg-bar span {
  background: #faad14;
}
.agg-card.tone-bad .agg-bar span {
  background: #f5222d;
}
.agg-detail {
  font-size: 11px;
  color: #999;
}

/* 工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.op {
  color: #999;
}
.filter-count {
  margin-left: auto;
  font-size: 12px;
  color: #999;
}

/* 逐题表 */
.metric-table :deep(table) {
  table-layout: fixed;
}
.q-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #333;
}
.cell-tone-good {
  color: #52c41a;
  font-weight: 600;
}
.cell-tone-bad {
  color: #f5222d;
}
.expand {
  font-size: 12px;
  color: #666;
  padding: 4px 8px;
  line-height: 1.8;
}
.docs {
  color: #999;
}

/* 解读抽屉 */
.guide-tip-top {
  font-size: 12px;
  color: #1890ff;
  background: #e6f7ff;
  padding: 8px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
}
.guide-row {
  border-top: 1px solid #f0f0f0;
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
  color: #333;
}
.g-stage,
.g-dir {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 8px;
  background: #f5f5f5;
  color: #666;
}
.g-value {
  margin-left: auto;
  font-size: 11px;
  color: #1890ff;
  font-weight: 600;
}
.g-desc,
.g-judge {
  font-size: 12px;
  color: #666;
  line-height: 1.7;
  margin-top: 2px;
}
.g-judge {
  color: #333;
}
.g-tip {
  border-top: 1px dashed #f0f0f0;
  padding-top: 10px;
  margin-top: 12px;
  font-size: 12px;
  color: #999;
  line-height: 1.7;
}

/* 检索参数条 */
.param-bar {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  padding: 10px 16px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  margin-bottom: 14px;
  font-size: 13px;
}
.param-title {
  font-weight: 600;
  color: #333;
}
.param-item {
  color: #666;
}
.param-item b {
  color: #1890ff;
  margin-left: 3px;
}

.empty {
  text-align: center;
  padding: 24px;
  color: #999;
  font-size: 13px;
}

/* 改写开关着色 */
.param-item b.on {
  color: #52c41a;
}
.param-item b.off {
  color: #999;
}
.muted {
  color: #d9d9d9;
}
.remark-chip {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: #1890ff;
  background: #e6f7ff;
  padding: 1px 8px;
  border-radius: 8px;
}

/* 重评历史（展开行内） */
.reevals {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #f0f0f0;
}
.reevals-title {
  font-size: 12px;
  color: #1890ff;
  margin-bottom: 6px;
  font-weight: 600;
}
.reeval-item {
  font-size: 12px;
  color: #666;
  padding: 4px 0;
  line-height: 1.8;
}
.rv-attempt {
  display: inline-block;
  font-weight: 600;
  color: #333;
  margin-right: 6px;
}
.rv-tag {
  margin-right: 6px;
}
.rv-hit {
  margin-right: 8px;
  color: #333;
}
.rv-score {
  margin-right: 8px;
}
.rv-exp {
  display: block;
  color: #1890ff;
}
.rv-docs {
  display: block;
  color: #999;
}
.rv-error {
  color: #f5222d;
}
.rv-remark {
  color: #333;
  background: #fffbe6;
  padding: 2px 8px;
  border-radius: 4px;
  display: inline-block;
  margin-top: 2px;
}

/* 重评弹窗问题预览 */
.reeval-q {
  font-size: 13px;
  color: #333;
  background: #fafafa;
  padding: 8px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
  line-height: 1.6;
}
</style>
