<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ReloadOutlined, EyeOutlined, PlusOutlined, SearchOutlined, BulbOutlined } from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import {
  listBadcases, getBadcase, updateTriage, updateBadcaseStatus, createManualBadcase, badcaseStats, badcaseDomains,
  searchCandidates, draftAnswer, promoteBadcase,
  TRIAGE_STAGE_LABEL, ORIGIN_LABEL, BADCASE_STATUS_LABEL,
  type BadcaseView, type BadcaseStats, type CandidateDoc,
} from '../api/badcase'
import { listDatasets, type EvalDataset } from '../api/eval'
import { fmtTime } from '../utils/format'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'
import { useDrawerWidth } from '../composables/useDrawerWidth'
import DrawerResizer from './DrawerResizer.vue'

/**
 * bad case 归集管理页：归集（点踩/评测低分/巡检/手工）→ 归因（规则预判 + 人工确认）
 * → 补 ground truth → 转正为评测条目（两区制：归集池 DRAFT → 领域数据集 READY）。
 * 设计见 docs/plans/2026-10-11-badcase-collection.md。
 */

const POOL_NAME = 'badcase 归集池'
/** 统计条展示顺序：上游环节在前（先错先归因） */
const STAGE_ORDER = ['INTENT_MISROUTE', 'NO_RECALL', 'FILTERED_OUT', 'RANKING', 'GENERATION', 'UNKNOWN']

interface ErrResp { response?: { data?: { message?: string } } }

// ── 列表与统计 ──
const rows = ref<BadcaseView[]>([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const filterOrigin = ref<string | undefined>(undefined)
const filterDomain = ref<string | undefined>(undefined)
const filterStatus = ref<string | undefined>(undefined)
const filterKeyword = ref('')
const stats = ref<BadcaseStats | null>(null)
const domains = ref<string[]>([])

const pagination = computed(() => ({
  current: page.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50'],
  showTotal: (t: number) => `共 ${t} 条`,
}))

const originOptions = Object.entries(ORIGIN_LABEL).map(([value, label]) => ({ value, label }))
const statusOptions = Object.entries(BADCASE_STATUS_LABEL).map(([value, label]) => ({ value, label }))
const domainOptions = computed(() => domains.value.map((d) => ({ value: d, label: d })))

async function load() {
  loading.value = true
  try {
    const res = await listBadcases({
      origin: filterOrigin.value,
      domain: filterDomain.value,
      status: filterStatus.value,
      keyword: filterKeyword.value.trim() || undefined,
      page: page.value,
      size: pageSize.value,
    })
    rows.value = res.records
    total.value = res.total
  } catch {
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await badcaseStats()
  } catch {
    stats.value = null
  }
}

function onFilter() {
  page.value = 1
  load()
}

function onTableChange(pag: { current?: number; pageSize?: number }) {
  page.value = pag.current ?? 1
  pageSize.value = pag.pageSize ?? 10
  load()
}

function reloadAll() {
  load()
  loadStats()
}

onMounted(async () => {
  domains.value = await badcaseDomains().catch(() => [])
  await Promise.all([load(), loadStats()])
})

// ── 详情抽屉：证据 + 归因确认（宽度可拖，配 DrawerResizer / useDrawerWidth） ──
const drawerOpen = ref(false)
const {
  width: drawerWidth,
  startResize: startDrawerResize,
  reset: resetDrawerWidth,
  nudge: nudgeDrawerWidth,
} = useDrawerWidth({ storageKey: 'rag-badcase-detail-w', defaultWidth: 560, minWidth: 380, maxWidth: 1000 })
const detail = ref<BadcaseView | null>(null)
const detailLoading = ref(false)
const editStage = ref('')
const editNote = ref('')
const editDomain = ref<string | undefined>(undefined)
const savingTriage = ref(false)

/** 领域推荐：本次检索命中的集合名（点踩时用户答不出领域，但运行期系统已"知道"这份信号） */
const domainSuggestions = computed(() =>
  (detail.value?.evidence?.hitCollections || [])
    .map((h) => h.name)
    .filter((n): n is string => !!n))

/** 领域下拉选项：字典 + 命中集合推荐（推荐值不在字典时补进去，保证能选中与回显） */
const domainSelectOptions = computed(() => {
  const base = domains.value.map((d) => ({ value: d, label: d }))
  const extra = domainSuggestions.value
    .filter((s) => !domains.value.includes(s))
    .map((s) => ({ value: s, label: `${s}（命中集合）` }))
  return [...base, ...extra]
})

async function openDetail(row: BadcaseView) {
  drawerOpen.value = true
  detailLoading.value = true
  try {
    detail.value = await getBadcase(row.id)
    editStage.value = detail.value.triage?.stage || 'UNKNOWN'
    editNote.value = detail.value.triage?.note || ''
    editDomain.value = detail.value.domain || undefined
    evAnswerExpanded.value = false
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

/* 当时的回答：长文折叠（对齐轨迹的 220 字预览 + 展开），不再靠 max-height 滚动 */
const EV_ANSWER_PREVIEW = 220
const evAnswerExpanded = ref(false)
const evAnswerText = computed(() => detail.value?.evidence?.answer ?? '')
const evAnswerHasMore = computed(() => evAnswerText.value.length > EV_ANSWER_PREVIEW)
const evAnswerBody = computed(() =>
  evAnswerExpanded.value || !evAnswerHasMore.value
    ? evAnswerText.value
    : `${evAnswerText.value.slice(0, EV_ANSWER_PREVIEW)}…`)

async function saveTriage() {
  if (!detail.value) return
  savingTriage.value = true
  try {
    detail.value = await updateTriage(detail.value.id, editStage.value,
      editNote.value || undefined, editDomain.value || undefined)
    editNote.value = detail.value.triage?.note || ''
    editDomain.value = detail.value.domain || undefined
    message.success('归因与领域已确认')
    reloadAll()
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '保存失败')
  } finally {
    savingTriage.value = false
  }
}

async function toggleIgnore(row: BadcaseView) {
  const ignored = row.badcaseStatus === 'IGNORED'
  Modal.confirm({
    title: ignored ? '恢复这条 bad case？' : '忽略这条 bad case？',
    content: ignored ? '恢复后重新进入待处理（DRAFT）。' : '忽略后不再出现在默认处理流里（可随时恢复）。',
    okText: ignored ? '恢复' : '忽略',
    cancelText: '取消',
    async onOk() {
      try {
        await updateBadcaseStatus(row.id, ignored ? 'DRAFT' : 'IGNORED')
        message.success(ignored ? '已恢复' : '已忽略')
        reloadAll()
        if (detail.value?.id === row.id) detail.value = await getBadcase(row.id)
      } catch (e: unknown) {
        message.error((e as ErrResp)?.response?.data?.message || '操作失败')
      }
    },
  })
}

// ── 转正：候选检索 + 起草标准答案 + 搬家 ──
const promoteOpen = ref(false)
const datasets = ref<EvalDataset[]>([])
const targetDatasetId = ref<number | undefined>(undefined)
const candidates = ref<CandidateDoc[]>([])
const selectedDocIds = ref<string[]>([])
const expectedAnswer = ref('')
const searching = ref(false)
const drafting = ref(false)
const promoting = ref(false)

const targetOptions = computed(() =>
  datasets.value.filter((d) => d.name !== POOL_NAME).map((d) => ({ value: d.id, label: d.name })))

async function openPromote() {
  if (!detail.value) return
  if (detail.value.badcaseStatus !== 'DRAFT') {
    message.info('只有「待补标准答案」状态的 bad case 可以转正')
    return
  }
  promoteOpen.value = true
  candidates.value = []
  selectedDocIds.value = []
  expectedAnswer.value = detail.value.expectedAnswer || ''
  if (!datasets.value.length) {
    try {
      datasets.value = await listDatasets()
    } catch { /* 保持空态，下拉里会有提示 */ }
  }
  // 领域已确认过 → 预选同名领域数据集（归集口径 = 转正口径，少点一步）
  targetDatasetId.value = datasets.value.find((d) => d.name === detail.value?.domain)?.id
}

async function doSearch() {
  if (!detail.value) return
  searching.value = true
  try {
    candidates.value = await searchCandidates(detail.value.id, targetDatasetId.value, 20)
    if (!candidates.value.length) {
      message.info('没有检索到候选文档（检查目标数据集是否关联了文档集合）')
    }
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '检索失败')
  } finally {
    searching.value = false
  }
}

function toggleDoc(doc: CandidateDoc) {
  if (!doc.docId) return
  const i = selectedDocIds.value.indexOf(doc.docId)
  if (i >= 0) selectedDocIds.value.splice(i, 1)
  else selectedDocIds.value.push(doc.docId)
}

async function doDraft() {
  if (!detail.value || !selectedDocIds.value.length) return
  drafting.value = true
  try {
    expectedAnswer.value = await draftAnswer(detail.value.id, selectedDocIds.value)
    message.success('已起草，请人工修订后提交')
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '起草失败')
  } finally {
    drafting.value = false
  }
}

async function doPromote() {
  if (!detail.value || !targetDatasetId.value) {
    message.warning('请选择目标数据集')
    return
  }
  if (!selectedDocIds.value.length) {
    message.warning('请至少勾选一篇期望文档（ground truth）')
    return
  }
  promoting.value = true
  try {
    detail.value = await promoteBadcase(detail.value.id, {
      targetDatasetId: targetDatasetId.value,
      expectedDocIds: selectedDocIds.value,
      expectedAnswer: expectedAnswer.value || undefined,
      domain: detail.value.domain || undefined,
    })
    promoteOpen.value = false
    message.success('已转正：条目进入目标数据集，下轮评测自动参与')
    reloadAll()
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '转正失败')
  } finally {
    promoting.value = false
  }
}

// ── 手工录入 ──
const manualOpen = ref(false)
const manualQuestion = ref('')
const manualNote = ref('')
const manualDomain = ref<string | undefined>(undefined)
const manualSubmitting = ref(false)

function openManual() {
  manualQuestion.value = ''
  manualNote.value = ''
  manualDomain.value = undefined
  manualOpen.value = true
}

async function submitManual() {
  if (!manualQuestion.value.trim()) {
    message.warning('请填写问题')
    return
  }
  manualSubmitting.value = true
  try {
    await createManualBadcase({
      question: manualQuestion.value.trim(),
      note: manualNote.value.trim() || undefined,
      domain: manualDomain.value,
    })
    manualOpen.value = false
    message.success('已录入归集池')
    reloadAll()
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '录入失败')
  } finally {
    manualSubmitting.value = false
  }
}

// ── 表格 ──
const STAGE_COLOR: Record<string, string> = {
  INTENT_MISROUTE: 'orange',
  NO_RECALL: 'red',
  FILTERED_OUT: 'volcano',
  RANKING: 'gold',
  GENERATION: 'purple',
  UNKNOWN: 'default',
}
const STATUS_COLOR: Record<string, string> = { DRAFT: 'blue', READY: 'green', IGNORED: 'default' }
const ORIGIN_COLOR: Record<string, string> = {
  online_dislike: 'geekblue',
  eval_low: 'cyan',
  trace_pick: 'purple',
  manual: 'default',
}

const columns = useResizableColumns([
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '来源', dataIndex: 'origin', key: 'origin', width: 96 },
  { title: '领域', dataIndex: 'domain', key: 'domain', width: 110, ellipsis: true },
  { title: '归因环节', dataIndex: 'stage', key: 'stage', width: 104 },
  { title: '状态', dataIndex: 'badcaseStatus', key: 'badcaseStatus', width: 112 },
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 156 },
  { title: '操作', key: 'action', width: 132, fixed: 'right' as const },
])

const tableData = computed(() => rows.value.map((r) => ({ ...r, stage: r.triage?.stage || 'UNKNOWN' })))

function stageLabel(v?: string | null): string {
  return v ? (TRIAGE_STAGE_LABEL[v] || v) : '—'
}
function fmtScore(v?: number | null): string {
  return v == null ? '—' : v.toFixed(3)
}
</script>

<template>
  <div class="badcase-panel page-scroll">
    <div class="page-header">
      <div>
        <div class="page-title">Bad Case 归集</div>
        <div class="page-desc">
          点踩 / 评测低分 / 巡检 / 手工归集的失败案例：规则归因 → 人工确认 → 补 ground truth → 转正为评测条目
        </div>
      </div>
      <div class="page-actions">
        <a-button @click="openManual"><template #icon><PlusOutlined /></template>手工录入</a-button>
        <a-button @click="reloadAll"><template #icon><ReloadOutlined /></template>刷新</a-button>
      </div>
    </div>

    <!-- 统计条：环节分布（针对性优化的依据） -->
    <div class="stat-row">
      <div class="stat-card stat-total">
        <div class="stat-num num">{{ stats?.total ?? 0 }}</div>
        <div class="stat-label">总数</div>
      </div>
      <div v-for="s in STAGE_ORDER" :key="s" class="stat-card">
        <div class="stat-num num">{{ stats?.byStage?.[s] ?? 0 }}</div>
        <div class="stat-label">{{ stageLabel(s) }}</div>
      </div>
    </div>

    <!-- 筛选卡 -->
    <div class="filter-card">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">来源</span>
          <a-select v-model:value="filterOrigin" placeholder="全部" allow-clear class="w-sm"
                    :options="originOptions" @change="onFilter" />
        </div>
        <div class="filter-item">
          <span class="filter-label">领域</span>
          <a-select v-model:value="filterDomain" placeholder="全部" allow-clear class="w-sm"
                    :options="domainOptions" @change="onFilter" />
        </div>
        <div class="filter-item">
          <span class="filter-label">状态</span>
          <a-select v-model:value="filterStatus" placeholder="全部" allow-clear class="w-sm"
                    :options="statusOptions" @change="onFilter" />
        </div>
        <div class="filter-item">
          <span class="filter-label">关键词</span>
          <a-input v-model:value="filterKeyword" placeholder="问题关键词" allow-clear class="w-lg"
                   @press-enter="onFilter" />
        </div>
        <div class="filter-actions">
          <a-button type="primary" @click="onFilter">查询</a-button>
        </div>
      </div>
    </div>

    <!-- 表格卡 -->
    <div class="table-card">
      <div class="table-toolbar">
        <span class="toolbar-hint">待补标准答案（DRAFT）的条目补完 ground truth 后可「转正」进入领域数据集参与评测</span>
      </div>
      <a-table
        :data-source="tableData"
        :columns="columns"
        :loading="loading"
        :pagination="pagination"
        @change="onTableChange"
        size="middle"
        row-key="id"
        :scroll="{ x: 880 }"
      >
        <template #headerCell="{ column }">
          <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="columns">{{ column.title }}</span>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'question'">
            <a class="bc-question" :title="record.question" @click="openDetail(record)">{{ record.question }}</a>
          </template>
          <template v-else-if="column.key === 'origin'">
            <a-tag v-if="record.origin" :color="ORIGIN_COLOR[record.origin] || 'default'">
              {{ ORIGIN_LABEL[record.origin] || record.origin }}
            </a-tag>
            <span v-else class="muted">—</span>
          </template>
          <template v-else-if="column.key === 'domain'">
            <span v-if="record.domain">{{ record.domain }}</span>
            <span v-else class="muted">未分领域</span>
          </template>
          <template v-else-if="column.key === 'stage'">
            <a-tooltip :title="record.triage?.note || ''">
              <a-tag :color="STAGE_COLOR[record.stage] || 'default'">{{ stageLabel(record.stage) }}</a-tag>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'badcaseStatus'">
            <a-tag :color="STATUS_COLOR[record.badcaseStatus || ''] || 'default'">
              {{ BADCASE_STATUS_LABEL[record.badcaseStatus || ''] || record.badcaseStatus || '—' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'createTime'">
            <span class="num">{{ fmtTime(record.createTime) }}</span>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openDetail(record)">
              <template #icon><EyeOutlined /></template>详情
            </a-button>
            <a-button v-if="record.badcaseStatus !== 'READY'" type="link" size="small" @click="toggleIgnore(record)">
              {{ record.badcaseStatus === 'IGNORED' ? '恢复' : '忽略' }}
            </a-button>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 详情抽屉：证据快照 + 归因确认 + 转正入口（左缘可拖宽，reveal 常显） -->
    <DrawerResizer
      v-if="drawerOpen"
      :width="drawerWidth"
      @start="startDrawerResize"
      @reset="resetDrawerWidth"
      @nudge="nudgeDrawerWidth"
    />
    <a-drawer
      :open="drawerOpen"
      :width="drawerWidth"
      title="Bad Case 详情"
      @update:open="(v: boolean) => (drawerOpen = v)"
    >
      <a-spin :spinning="detailLoading">
        <div v-if="detail" class="bc-detail">
          <!-- 顶部读数行（对齐轨迹 trace-meta：tag + 等宽读数，一眼定位这条 case 的身份） -->
          <div class="bc-meta">
            <a-tag v-if="detail.origin" :color="ORIGIN_COLOR[detail.origin] || 'default'">
              {{ ORIGIN_LABEL[detail.origin] || detail.origin }}
            </a-tag>
            <a-tag v-if="detail.domain">{{ detail.domain }}</a-tag>
            <a-tag :color="STATUS_COLOR[detail.badcaseStatus || ''] || 'default'">
              {{ BADCASE_STATUS_LABEL[detail.badcaseStatus || ''] || detail.badcaseStatus }}
            </a-tag>
            <a-tag v-if="detail.feedbackReason" color="red">
              {{ (detail.feedbackReason && ({ answer_irrelevant: '答非所问', content_wrong: '内容错误', incomplete: '信息不全', not_found: '没找到资料', other: '其他' } as Record<string, string>)[detail.feedbackReason]) || detail.feedbackReason }}
            </a-tag>
            <span class="bc-meta-item mono">#{{ detail.id }}</span>
            <span class="bc-meta-item num">{{ fmtTime(detail.createTime) }}</span>
          </div>
          <div class="bc-question-block">{{ detail.question }}</div>
          <div v-if="detail.feedbackNote" class="bc-note">用户备注：{{ detail.feedbackNote }}</div>

          <!-- 归因与领域确认 -->
          <div class="bc-section">
            <div class="bc-section-title">
              归因与领域（规则预判 → 人工确认）
              <span class="bc-badge" :class="detail.triage?.auto ? 'bc-badge-warn' : 'bc-badge-ok'">
                {{ detail.triage?.auto ? '规则预判 · 待确认' : '已人工确认' }}
              </span>
            </div>
            <div class="bc-triage-row">
              <a-select v-model:value="editStage" style="width: 150px">
                <a-select-option v-for="(label, value) in TRIAGE_STAGE_LABEL" :key="value" :value="value">
                  {{ label }}
                </a-select-option>
              </a-select>
              <a-select v-model:value="editDomain" placeholder="领域" allow-clear style="width: 170px"
                        :options="domainSelectOptions" />
              <a-input v-model:value="editNote" placeholder="判定依据 / 备注" class="bc-triage-note" />
              <a-button type="primary" :loading="savingTriage" @click="saveTriage">确认</a-button>
            </div>
            <div v-if="domainSuggestions.length" class="bc-domain-suggest">
              命中集合（点击填入领域）：
              <a-tag v-for="s in domainSuggestions" :key="s" class="bc-suggest-tag" @click="editDomain = s">
                {{ s }}
              </a-tag>
            </div>
            <div class="bc-triage-hint">
              {{ detail.triage?.auto ? '当前为规则自动预判（可改）' : '已人工确认（规则不再覆盖）' }}
            </div>
          </div>

          <!-- 证据快照：轨迹同款时间线（色点 + 行头徽标读数 + 分块卡片） -->
          <div v-if="detail.evidence" class="bc-section">
            <div class="bc-section-title">证据快照（归集时固化）</div>
            <div v-if="detail.evidence.note" class="bc-ev-note">{{ detail.evidence.note }}</div>
            <div v-if="detail.evidence.answer" class="bc-ev-answer">
              {{ evAnswerBody }}
              <button v-if="evAnswerHasMore" type="button" class="bc-link-btn" @click="evAnswerExpanded = !evAnswerExpanded">
                {{ evAnswerExpanded ? '收起' : '展开' }}
              </button>
            </div>

            <div class="bc-timeline">
              <!-- 意图分类 -->
              <div v-if="detail.evidence.intent" class="bc-tl-item">
                <span class="bc-dot bc-dot-condition" title="意图分类" />
                <div class="bc-tl-main">
                  <div class="bc-tl-head">
                    <span class="bc-tl-label">意图分类</span>
                    <span class="bc-badge" :class="detail.evidence.intent.needsRetrieval ? 'bc-badge-warn' : 'bc-badge-bad'">
                      {{ detail.evidence.intent.needsRetrieval ? '走了检索' : '未走检索' }}
                    </span>
                    <span class="bc-tl-meta mono">置信度 {{ detail.evidence.intent.confidence ?? '—' }}</span>
                  </div>
                  <div class="bc-io">
                    <div class="bc-io-line"><span class="bc-io-label">域</span><span class="bc-io-text">{{ detail.evidence.intent.domain || '—' }}</span></div>
                    <div v-if="detail.evidence.intent.reason" class="bc-io-line"><span class="bc-io-label">理由</span><span class="bc-io-text">{{ detail.evidence.intent.reason }}</span></div>
                  </div>
                </div>
              </div>

              <!-- 命中集合 -->
              <div v-if="detail.evidence.hitCollections?.length" class="bc-tl-item">
                <span class="bc-dot bc-dot-tool" title="命中集合" />
                <div class="bc-tl-main">
                  <div class="bc-tl-head">
                    <span class="bc-tl-label">命中集合</span>
                    <span class="bc-tl-meta mono">领域推荐依据</span>
                  </div>
                  <div class="bc-io">
                    <div class="bc-io-line">
                      <span class="bc-io-label">集合</span>
                      <span class="bc-io-text">
                        <span v-for="h in detail.evidence.hitCollections" :key="h.name ?? h.collectionId" class="bc-col-chip">
                          {{ h.name || h.collectionId }}<small>{{ h.hits }}</small>
                        </span>
                      </span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 检索情况 -->
              <div v-if="detail.evidence.retrieval" class="bc-tl-item">
                <span class="bc-dot bc-dot-tool" title="检索情况" />
                <div class="bc-tl-main">
                  <div class="bc-tl-head">
                    <span class="bc-tl-label">检索情况</span>
                    <span class="bc-badge" :class="(detail.evidence.retrieval.finalHits ?? 0) > 0 ? 'bc-badge-ok' : 'bc-badge-bad'">
                      {{ (detail.evidence.retrieval.finalHits ?? 0) > 0 ? `达标 ${detail.evidence.retrieval.finalHits} 条` : '无达标命中' }}
                    </span>
                    <span class="bc-tl-meta mono">候选 {{ detail.evidence.retrieval.channelCandidates ?? '—' }}</span>
                  </div>
                  <div class="bc-io">
                    <div v-if="detail.evidence.retrieval.channels?.length" class="bc-io-line">
                      <span class="bc-io-label">通道</span>
                      <span class="bc-io-text">
                        <span v-for="c in detail.evidence.retrieval.channels" :key="c.name" class="bc-col-chip">
                          {{ c.name }}<small>{{ c.hits }}</small>
                        </span>
                      </span>
                    </div>
                    <div v-for="(c, i) in detail.evidence.retrieval.chunks" :key="i" class="bc-ev-chunk">
                      <div class="bc-ev-chunk-head">
                        <span class="bc-ev-chunk-doc">{{ c.docName || c.docId || '未知文档' }}</span>
                        <span class="bc-ev-chunk-score num">{{ fmtScore(c.score) }}</span>
                        <span v-if="c.channel" class="bc-ev-chunk-channel">{{ c.channel }}</span>
                      </div>
                      <div class="bc-ev-chunk-preview">{{ c.preview }}</div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 回答引用 -->
              <div v-if="detail.evidence.citations?.length" class="bc-tl-item">
                <span class="bc-dot bc-dot-human" title="回答引用" />
                <div class="bc-tl-main">
                  <div class="bc-tl-head">
                    <span class="bc-tl-label">回答引用</span>
                    <span class="bc-badge">{{ detail.evidence.citations.length }} 份</span>
                  </div>
                  <div class="bc-io">
                    <div class="bc-io-line">
                      <span class="bc-io-label">来源</span>
                      <span class="bc-io-text">
                        <span v-for="(c, i) in detail.evidence.citations" :key="i" class="bc-col-chip">{{ c.docName || c.docId }}</span>
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="bc-detail-foot">
            <a-button v-if="detail.badcaseStatus === 'DRAFT'" type="primary" block @click="openPromote">
              补 ground truth 并转正
            </a-button>
            <div v-else-if="detail.badcaseStatus === 'READY'" class="bc-ready-hint">
              已转正到数据集 #{{ detail.datasetId }}，参与评测（期望文档 {{ detail.expectedDocCount }} 篇）
            </div>
          </div>
        </div>
      </a-spin>
    </a-drawer>

    <!-- 转正弹窗：检索候选 → 勾选 → 起草标准答案 → 提交 -->
    <a-modal
      v-model:open="promoteOpen"
      title="转正为评测条目"
      width="720px"
      :confirm-loading="promoting"
      ok-text="提交转正"
      cancel-text="取消"
      @ok="doPromote"
    >
      <div class="pm-step">
        <div class="pm-label">① 目标数据集（领域）</div>
        <a-select v-model:value="targetDatasetId" placeholder="选择领域数据集" style="width: 100%"
                  :options="targetOptions" show-search option-filter-prop="label" />
      </div>
      <div class="pm-step">
        <div class="pm-label">
          ② 期望文档（ground truth）
          <a-button size="small" :loading="searching" style="margin-left: 8px" @click="doSearch">
            <template #icon><SearchOutlined /></template>在目标集合内检索候选
          </a-button>
          <span v-if="selectedDocIds.length" class="pm-picked">已选 {{ selectedDocIds.length }} 篇</span>
        </div>
        <div v-if="candidates.length" class="pm-candidates">
          <div
            v-for="(c, i) in candidates"
            :key="i"
            class="pm-candidate"
            :class="{ picked: c.docId && selectedDocIds.includes(c.docId) }"
            @click="toggleDoc(c)"
          >
            <div class="pm-candidate-head">
              <span class="pm-candidate-doc">{{ c.docName || c.docId }}</span>
              <span class="pm-candidate-score num">{{ fmtScore(c.score) }}</span>
            </div>
            <div class="pm-candidate-preview">{{ c.preview }}</div>
          </div>
        </div>
        <div v-else class="pm-empty">点上方按钮检索候选文档（badcase 本身没召回对，需要重新检索）</div>
      </div>
      <div class="pm-step">
        <div class="pm-label">
          ③ 标准答案
          <a-button size="small" style="margin-left: 8px" :disabled="!selectedDocIds.length" :loading="drafting" @click="doDraft">
            <template #icon><BulbOutlined /></template>基于选中文档起草
          </a-button>
        </div>
        <a-textarea v-model:value="expectedAnswer" :rows="5" placeholder="可先起草再人工修订；留空则本条不参与答案质量评测" />
      </div>
      <div class="pm-hint">提交后条目搬到目标数据集并置为「已转正」，下次评测自动被抽中</div>
    </a-modal>

    <!-- 手工录入弹窗 -->
    <a-modal
      v-model:open="manualOpen"
      title="手工录入 bad case"
      :confirm-loading="manualSubmitting"
      ok-text="录入归集池"
      cancel-text="取消"
      @ok="submitManual"
    >
      <div class="pm-label">问题（必填）</div>
      <a-textarea v-model:value="manualQuestion" :rows="3" placeholder="人工发现的失败问题（无线上对话现场）" />
      <div class="pm-label" style="margin-top: 10px">备注（可选）</div>
      <a-textarea v-model:value="manualNote" :rows="2" placeholder="来源 / 期望答案 / 复现路径" />
      <div class="pm-label" style="margin-top: 10px">领域（可选）</div>
      <a-select v-model:value="manualDomain" placeholder="选择领域" allow-clear style="width: 100%"
                :options="domainOptions" />
    </a-modal>
  </div>
</template>

<style scoped>
/* 根布局走全局 .page-scroll（height/overflow/padding，全站列表页骨架）；此处只留面板私有样式 */

/* 统计条：环节分布 */
.stat-row { display: flex; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.stat-card {
  flex: 1 1 110px; min-width: 110px; padding: 12px 16px;
  background: var(--color-surface); border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
}
.stat-total { border-left: 3px solid var(--color-primary); }
.stat-num { font-size: 20px; font-weight: 700; color: var(--color-ink); line-height: 1.3; }
.stat-label { font-size: 11.5px; color: var(--color-ink-tertiary); }

.bc-question { color: var(--color-ink); cursor: pointer; }
.bc-question:hover { color: var(--color-primary); text-decoration: underline; }
.muted { color: var(--color-ink-tertiary); }

/* 详情抽屉 */
.bc-detail { display: flex; flex-direction: column; gap: 12px; }
/* 顶部读数行（对齐 AgentTraceTree 的 trace-meta）：tag + 等宽读数 */
.bc-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; color: var(--color-ink-secondary); }
.bc-meta-item { font-size: 11px; color: var(--color-ink-tertiary); }
.bc-meta-item.mono { font-family: var(--font-display, monospace); }
.bc-question-block { font-size: 14px; font-weight: 600; line-height: 1.6; color: var(--color-ink); }
.bc-note { font-size: 12.5px; color: var(--color-ink-secondary); }
.bc-section { border-top: 1px dashed var(--color-border-light); padding-top: 10px; }
.bc-section-title { display: flex; align-items: center; gap: 8px; font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--color-ink-tertiary); margin-bottom: 8px; }
/* 归因确认：抽屉宽 560px，一行放不下四个控件——归因/领域/确认一行，备注整行换行 */
.bc-triage-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.bc-triage-note { flex: 1 1 100%; margin-top: 2px; }
.bc-triage-hint { margin-top: 6px; font-size: 11.5px; color: var(--color-ink-tertiary); }
/* 命中集合推荐：点击即填入领域（运行期信号，用户点踩时答不出的领域由系统带出来） */
.bc-domain-suggest { margin-top: 8px; font-size: 11.5px; color: var(--color-ink-tertiary); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.bc-suggest-tag { cursor: pointer; margin: 0; }
.bc-suggest-tag:hover { color: var(--color-primary); border-color: var(--color-primary); background: var(--color-primary-light); }
.bc-ev-note { font-size: 12px; color: var(--color-warning-ink, #d46b08); background: var(--color-signal-bg); border-radius: var(--radius-sm); padding: 6px 10px; margin-bottom: 8px; }
/* 当时的回答：220 字预览 + 展开（对齐轨迹），不再 max-height 滚动 */
.bc-ev-answer { font-size: 12.5px; color: var(--color-ink-secondary); line-height: 1.6; white-space: pre-wrap; background: var(--color-surface-secondary); border-radius: var(--radius-sm); padding: 8px 10px; margin-bottom: 8px; }
.bc-link-btn { border: none; background: none; padding: 0 4px; font-size: 11px; color: var(--color-primary); cursor: pointer; }

/* 证据快照时间线（结构对齐 AgentTraceTree——色点 + 行头徽标读数 + io 分块卡片） */
.bc-timeline { display: flex; flex-direction: column; }
.bc-tl-item { position: relative; padding: 0 0 14px 20px; }
.bc-tl-item:last-child { padding-bottom: 2px; }
.bc-tl-item::before {
  content: ''; position: absolute; left: 4px; top: 14px; bottom: -4px; width: 1px;
  background: var(--color-border-light);
}
.bc-tl-item:last-child::before { display: none; }
/* 色点语义对齐轨迹：条件琥珀 / 工具蓝 / 人工绿 */
.bc-dot {
  position: absolute; left: 0; top: 5px; width: 9px; height: 9px; border-radius: 50%;
  border: 2px solid var(--color-surface); box-shadow: 0 0 0 1px var(--color-border-light);
}
.bc-dot-condition { background: #d4880f; }
.bc-dot-tool { background: #2f7ff5; }
.bc-dot-human { background: #389e0d; }
.bc-tl-head { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-bottom: 4px; }
.bc-tl-label { font-weight: 600; font-size: 12.5px; color: var(--color-ink); }
.bc-tl-meta { margin-left: auto; font-size: 12px; color: var(--color-ink-secondary); }
.bc-tl-meta.mono {
  font-family: var(--font-display, monospace); font-variant-numeric: tabular-nums;
  font-weight: 500; color: var(--color-ink);
}
/* 徽标（对齐轨迹 badge：命中绿 / 无命中红 / 提示琥珀） */
.bc-badge {
  padding: 0 6px; border-radius: 9px; font-size: 11px; line-height: 17px;
  background: var(--color-surface); border: 1px solid var(--color-border-light);
}
.bc-badge-ok { color: #389e0d; border-color: rgba(56, 158, 13, 0.35); background: rgba(56, 158, 13, 0.05); }
.bc-badge-bad { color: #cf1322; border-color: rgba(207, 19, 34, 0.35); background: rgba(207, 19, 34, 0.05); }
.bc-badge-warn { color: #d4880f; border-color: rgba(212, 136, 15, 0.35); background: rgba(212, 136, 15, 0.06); }
/* io 分块卡片（对齐轨迹 .io：左缩进 + 浅底 + 左边框） */
.bc-io {
  display: flex; flex-direction: column; gap: 4px;
  padding: 6px 10px 7px;
  border-left: 2px solid var(--color-border-light);
  background: var(--color-surface-secondary);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}
.bc-io-line { display: flex; flex-direction: column; gap: 2px; }
.bc-io-label {
  align-self: flex-start; font-size: 10px; line-height: 18px; padding: 0 6px; border-radius: 4px;
  color: var(--color-ink-tertiary); background: var(--color-surface);
}
.bc-io-text { font-size: 11.5px; color: var(--color-ink-secondary); line-height: 1.6; word-break: break-all; }
/* 集合 / 通道 / 引用来源 chip（对齐轨迹 ref-chip） */
.bc-col-chip {
  display: inline-flex; align-items: baseline; gap: 3px; margin: 1px 4px 1px 0; padding: 1px 7px;
  border-radius: 10px; font-size: 11px; background: var(--color-surface);
  border: 1px solid var(--color-border-light); color: var(--color-ink-secondary);
}
.bc-col-chip small { font-size: 10px; color: var(--color-ink-tertiary); font-variant-numeric: tabular-nums; }
.bc-ev-chunk { border: 1px solid var(--color-border-light); border-radius: var(--radius-sm); padding: 6px 8px; margin-top: 6px; background: var(--color-surface); }
.bc-ev-chunk-head { display: flex; align-items: baseline; gap: 8px; }
.bc-ev-chunk-doc { flex: 1; min-width: 0; font-size: 12px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bc-ev-chunk-score { font-size: 11.5px; color: var(--color-primary); font-family: var(--font-display, monospace); font-variant-numeric: tabular-nums; }
.bc-ev-chunk-channel { font-size: 10.5px; color: var(--color-ink-tertiary); }
.bc-ev-chunk-preview { margin-top: 3px; font-size: 11.5px; color: var(--color-ink-tertiary); line-height: 1.55; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.bc-detail-foot { border-top: 1px dashed var(--color-border-light); padding-top: 12px; }
.bc-ready-hint { font-size: 12.5px; color: var(--color-ink-secondary); background: var(--color-success-bg, #f6ffed); border-radius: var(--radius-sm); padding: 8px 10px; }

/* 转正弹窗 */
.pm-step { margin-bottom: 14px; }
.pm-label { font-size: 12.5px; font-weight: 600; color: var(--color-ink-secondary); margin-bottom: 6px; display: flex; align-items: center; }
.pm-picked { margin-left: 10px; font-size: 11.5px; font-weight: 400; color: var(--color-primary); }
.pm-candidates { max-height: 260px; overflow-y: auto; border: 1px solid var(--color-border-light); border-radius: var(--radius-sm); }
.pm-candidate { padding: 7px 10px; cursor: pointer; border-bottom: 1px solid var(--color-border-light); transition: background .12s; }
.pm-candidate:last-child { border-bottom: none; }
.pm-candidate:hover { background: var(--color-hover-bg); }
.pm-candidate.picked { background: var(--color-primary-light); border-left: 3px solid var(--color-primary); }
.pm-candidate-head { display: flex; align-items: baseline; gap: 8px; }
.pm-candidate-doc { flex: 1; min-width: 0; font-size: 12.5px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pm-candidate-score { font-size: 11.5px; color: var(--color-primary); }
.pm-candidate-preview { margin-top: 2px; font-size: 11.5px; color: var(--color-ink-tertiary); line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pm-empty { font-size: 12px; color: var(--color-ink-tertiary); padding: 10px; background: var(--color-surface-secondary); border-radius: var(--radius-sm); }
.pm-hint { font-size: 11.5px; color: var(--color-ink-tertiary); border-top: 1px dashed var(--color-border-light); padding-top: 8px; }
</style>
