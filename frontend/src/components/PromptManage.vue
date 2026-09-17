<script setup lang="ts">
/**
 * Prompt 资产管理（P1）：左栏按域分组的 key 树（命名即分层：chat/agent/ingestion/workflow/…），
 * 右栏内容优先的工作区——内容（默认展示当前版本全文）/ 历史（紧凑版本表）双 tab，
 * diff 与发新版本为独立专注态。版本不可变：编辑=发新版本，回滚=以旧版本内容发新版本。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  AppstoreOutlined,
  CopyOutlined,
  DatabaseOutlined,
  DiffOutlined,
  DownOutlined,
  EditOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  RollbackOutlined,
  SearchOutlined,
  SyncOutlined,
} from '@ant-design/icons-vue'
import PromptBundleDrawer from './PromptBundleDrawer.vue'
import {
  createPromptVersion,
  listPromptKeys,
  listPromptVersions,
  rollbackPrompt,
  syncPromptFromCode,
  type PromptKeyView,
  type PromptVersion,
} from '../api/prompt'
import { getRegistry, type AgentView } from '../api/agentRegistry'
import { EMPTY, fmtTime } from '../utils/format'

// ===== 左栏：key 域分组树 =====
const keys = ref<PromptKeyView[]>([])
const loadingKeys = ref(false)
const search = ref('')
const driftOnly = ref(false)

const driftCount = computed(() => keys.value.filter((k) => k.driftedFromCode).length)

// ===== 分层：基础（平台公共）/ 功能（工作流特化）/ 离线（数据加工）=====
// 规则从 key 命名推导（chat/*、agent 单段=公共；agent/{wf}/*、workflow/*=特化；ingestion/*=离线），
// 新增 key 零维护。
type TierKey = 'base' | 'feature' | 'offline'

interface TierDef {
  key: TierKey
  name: string
  badge: string
  desc: string
}

const TIERS: TierDef[] = [
  { key: 'base', name: '基础', badge: '基础 · 平台公共', desc: '查询链与 Agent 共享，改动影响全链路' },
  { key: 'feature', name: '功能', badge: '功能 · 工作流特化', desc: '只影响所属工作流 / 能力' },
  { key: 'offline', name: '离线', badge: '离线 · 数据加工', desc: '入库链路使用，不参与在线问答' },
]

/** key → 分层 + 域前缀 */
function classifyKey(key: string): { tier: TierKey; domain: string } {
  const seg = key.split('/')
  const head = seg[0] ?? 'other'
  if (head === 'chat') return { tier: 'base', domain: 'chat' }
  if (head === 'agent') {
    return seg.length <= 2
      ? { tier: 'base', domain: 'agent' }
      : { tier: 'feature', domain: `agent/${seg[1]}` }
  }
  if (head === 'workflow') return { tier: 'feature', domain: `workflow/${seg[1]}` }
  if (head === 'ingestion') return { tier: 'offline', domain: 'ingestion' }
  return { tier: 'feature', domain: head }
}

// ===== 链路 → 环节 → key 组织（归类由后端集中声明；旧后端无 chain 字段时按目录前缀退化分组） =====
const CHAIN_ORDER = ['rag', 'agent', 'ingestion', 'vlm', 'other', '_all']

interface SegGroup {
  segKey: string
  label: string
  keys: PromptKeyView[]
}

interface ChainGroup {
  chainKey: string
  code: string
  label: string
  segs: SegGroup[]
  total: number
}

const chainGroups = computed<ChainGroup[]>(() => {
  const q = search.value.trim().toLowerCase()
  const match = (k: PromptKeyView) =>
    (!q || k.promptKey.toLowerCase().includes(q)) && (!driftOnly.value || k.driftedFromCode)
  const byChain = new Map<string, ChainGroup>()
  for (const k of keys.value) {
    if (!match(k)) continue
    const code = k.chain ?? '_all'
    let c = byChain.get(code)
    if (!c) {
      c = { chainKey: 'chain:' + code, code, label: k.chainLabel ?? '全部 Prompt', segs: [], total: 0 }
      byChain.set(code, c)
    }
    // 环节兜底：无 segmentLabel 时取 key 目录前缀（chat/intent/x → chat/intent）
    const segLabel = k.segmentLabel ?? (k.promptKey.split('/').slice(0, -1).join('/') || '根')
    const segKey = 'seg:' + code + ':' + (k.segment ?? segLabel)
    let seg = c.segs.find((s) => s.segKey === segKey)
    if (!seg) {
      seg = { segKey, label: segLabel, keys: [] }
      c.segs.push(seg)
    }
    seg.keys.push(k)
    c.total++
  }
  const out = [...byChain.values()]
  out.sort((a, b) => {
    const ia = CHAIN_ORDER.indexOf(a.code)
    const ib = CHAIN_ORDER.indexOf(b.code)
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib)
  })
  for (const c of out) c.segs.sort((a, b) => a.label.localeCompare(b.label, 'zh'))
  return out
})

/** 链路识别图标（卡片头） */
const CHAIN_ICONS: Record<string, unknown> = {
  rag: SearchOutlined,
  agent: RobotOutlined,
  ingestion: DatabaseOutlined,
  vlm: EyeOutlined,
  _all: FolderOpenOutlined,
}

function chainIcon(code: string): unknown {
  return CHAIN_ICONS[code] ?? SearchOutlined
}

/** key 条目显示末段（环节已分组，前缀冗余；精确匹配环节或无环节时显示全名） */
function tailOf(k: PromptKeyView): string {
  const seg = k.segment
  if (seg && k.promptKey !== seg) {
    const prefix = seg.endsWith('/') ? seg : seg + '/'
    if (k.promptKey.startsWith(prefix)) return k.promptKey.slice(prefix.length)
  }
  return k.promptKey
}

/** 页头统计行 */
const statsLine = computed(() => {
  const n = keys.value.length
  const c = new Set(keys.value.map((k) => k.chain).filter(Boolean)).size
  return `${n} 个 prompt · ${c || 1} 条链路${driftCount.value ? ` · ${driftCount.value} 处代码差异` : ''}`
})

// ===== 分组折叠（链路 / 环节两级；localStorage 持久化） =====
const COLLAPSE_STORE = 'rag_prompt_collapsed'

function loadCollapsed(): Set<string> {
  // 无历史记录时默认折叠全部链路（首屏只看链路目录，点开想看的）；有记录则尊重用户选择
  const defaultCollapsed = () => new Set(CHAIN_ORDER.map((c) => 'chain:' + c))
  try {
    const raw = localStorage.getItem(COLLAPSE_STORE)
    if (raw == null) return defaultCollapsed()
    return new Set(JSON.parse(raw) as string[])
  } catch {
    return defaultCollapsed()
  }
}

const collapsed = ref(loadCollapsed())

function toggleCollapse(k: string) {
  const s = new Set(collapsed.value)
  if (s.has(k)) s.delete(k)
  else s.add(k)
  collapsed.value = s
  localStorage.setItem(COLLAPSE_STORE, JSON.stringify([...s]))
}

// ===== registry 引用关系（资产头「引用方」联动展示用） =====
const agents = ref<AgentView[]>([])

async function loadAgents() {
  try {
    const { data } = await getRegistry()
    agents.value = data.agents
  } catch {
    /* registry 不可用时引用方行不渲染 */
  }
}

/** 选中 key 的引用方（agent × 角色），资产头联动展示 */
interface Consumer {
  agentType: string
  agentLabel: string
  role: string
}

const consumers = computed<Consumer[]>(() => {
  const k = selectedKey.value
  if (!k) return []
  const out: Consumer[] = []
  for (const a of agents.value) {
    const roles: string[] = []
    if ((a.agentPromptKeys ?? []).includes(k)) roles.push('人格层')
    if (a.answerPromptKey === k) roles.push('答案生成')
    if (a.slotExtractPromptKey === k) roles.push('抽槽')
    if (a.replanPromptKey === k) roles.push('replan 裁决')
    const stageNames = (a.stageDetails ?? []).filter((s) => s.systemPromptKey === k).map((s) => s.name)
    if (stageNames.length) roles.push('阶段 ' + stageNames.join('/'))
    if (!roles.length && (a.workflowPromptKeys ?? []).includes(k)) roles.push('流程层')
    if ((a.linkPromptKeys ?? []).includes(k)) roles.push('链路层')
    if (roles.length) out.push({ agentType: a.type, agentLabel: a.label, role: roles.join(' · ') })
  }
  return out
})

const selectedMeta = computed(() => keys.value.find((k) => k.promptKey === selectedKey.value) ?? null)
const selectedClass = computed(() => (selectedKey.value ? classifyKey(selectedKey.value) : null))

/** 选中 key 的角色说明（影响面），资产头展示 */
const roleText = computed(() => {
  const c = selectedClass.value
  if (!c) return null
  if (c.tier === 'base') return { badge: '基础 · 平台公共', desc: '查询链与 Agent 共享，改动影响全链路', tier: c.tier }
  if (c.tier === 'offline') return { badge: '离线 · 数据加工', desc: '入库链路使用，不参与在线问答', tier: c.tier }
  return { badge: '功能 · 工作流特化', desc: `只影响 ${c.domain} 工作流`, tier: c.tier }
})

function restOf(k: PromptKeyView, domain: string): string {
  return k.promptKey.slice(domain.length + 1) || k.promptKey
}

async function loadKeys() {
  loadingKeys.value = true
  try {
    const { data } = await listPromptKeys()
    keys.value = data
  } catch {
    message.error('加载 prompt 列表失败')
  } finally {
    loadingKeys.value = false
  }
}

// ===== 新建 prompt（线上建档 v1；key 命名规范与后端一致） =====
// ===== 能力包抽屉 =====
const bundleOpen = ref(false)

// ===== 新建 prompt（线上建档 v1；key 命名规范与后端一致） =====
const createOpen = ref(false)
const createKey = ref('')
const createContent = ref('')
const createNote = ref('')
const creating = ref(false)

function openCreate() {
  createKey.value = ''
  createContent.value = ''
  createNote.value = ''
  createOpen.value = true
}

const createKeyErr = computed(() => {
  const k = createKey.value.trim()
  if (!k) return ''
  if (!/^[a-z0-9][a-z0-9/_-]{1,199}$/.test(k)) {
    return '命名不合法：小写字母/数字与 / _ -（如 agent/ops/new-step）'
  }
  if (keys.value.some((x) => x.promptKey === k)) {
    return 'key 已存在——请在列表选中后「发新版本」'
  }
  return ''
})

async function submitCreate() {
  const k = createKey.value.trim()
  if (!k || createKeyErr.value) {
    message.warning('请修正 key')
    return
  }
  if (!createContent.value.trim()) {
    message.warning('内容不能为空')
    return
  }
  creating.value = true
  try {
    const { data } = await createPromptVersion(k, createContent.value, createNote.value)
    message.success(`已创建 ${k}（v${data.versionNo}）`)
    createOpen.value = false
    await loadKeys()
    const meta = keys.value.find((x) => x.promptKey === k)
    if (meta) selectKey(meta)
  } catch {
    message.error('创建失败')
  } finally {
    creating.value = false
  }
}

function selectKey(k: PromptKeyView) {
  selectedKey.value = k.promptKey
  mode.value = 'read'
  readTab.value = 'content'
  versions.value = []
  viewVersion.value = null
  diffFromNo.value = undefined
  diffToNo.value = undefined
  if (!k.codeOnly) loadVersions()
}

// ===== 右栏：版本数据 =====
const selectedKey = ref<string | null>(null)
const versions = ref<PromptVersion[]>([])
const loadingVersions = ref(false)
const currentVersion = computed(() => versions.value[0] ?? null)

const mode = ref<'read' | 'diff' | 'edit'>('read')
const readTab = ref<'content' | 'history'>('content')
const viewVersion = ref<PromptVersion | null>(null)

async function loadVersions() {
  if (!selectedKey.value) return
  loadingVersions.value = true
  try {
    const { data } = await listPromptVersions(selectedKey.value)
    versions.value = data
    viewVersion.value = data[0] ?? null
  } catch {
    message.error('加载版本时间线失败')
  } finally {
    loadingVersions.value = false
  }
}

/** 时间短格式（同年省年份）：09-12 11:38；title 挂全量 */
function fmtShort(t: string | null | undefined): string {
  if (!t) return EMPTY
  return t.replace('T', ' ').slice(5, 16)
}

function showVersion(v: PromptVersion) {
  viewVersion.value = v
  readTab.value = 'content'
  mode.value = 'read'
}

async function copyContent() {
  if (!viewVersion.value) return
  try {
    await navigator.clipboard.writeText(viewVersion.value.content)
    message.success('已复制 v' + viewVersion.value.versionNo + ' 内容')
  } catch {
    message.error('复制失败')
  }
}

// ===== 写操作：发版 / 回滚 / 收编 =====
const editContent = ref('')
const editNote = ref('')
const publishing = ref(false)

function startEdit() {
  if (!currentVersion.value) return
  editContent.value = currentVersion.value.content
  editNote.value = ''
  mode.value = 'edit'
}

async function submitNewVersion() {
  if (!selectedKey.value) return
  if (!editContent.value.trim()) {
    message.warning('内容不能为空')
    return
  }
  publishing.value = true
  try {
    const { data } = await createPromptVersion(selectedKey.value, editContent.value, editNote.value)
    message.success(`已发布 v${data.versionNo}`)
    mode.value = 'read'
    readTab.value = 'content'
    await loadVersions()
    await loadKeys()
  } catch {
    message.error('发布失败')
  } finally {
    publishing.value = false
  }
}

async function doRollback(v: PromptVersion) {
  if (!selectedKey.value) return
  try {
    const { data } = await rollbackPrompt(selectedKey.value, v.versionNo)
    message.success(`已回滚至 v${v.versionNo} 的内容（新版本 v${data.versionNo}）`)
    await loadVersions()
    await loadKeys()
  } catch {
    message.error('回滚失败')
  }
}

/** 收编 / 建档同源：以 classpath 代码内容发新版本（codeOnly key 即建档 v1） */
async function doSyncFromCode() {
  if (!selectedKey.value) return
  try {
    const { data } = await syncPromptFromCode(selectedKey.value)
    message.success(`已以代码内容发布 v${data.versionNo}`)
    mode.value = 'read'
    readTab.value = 'content'
    await loadVersions()
    await loadKeys()
  } catch {
    message.error('操作失败')
  }
}

// ===== 逐行 diff（LCS，prompt 体量 O(n·m) 无压力；数据已在手不发请求） =====
interface DiffRow {
  type: 'same' | 'add' | 'del'
  text: string
}

function buildDiff(from: string, to: string): DiffRow[] {
  const a = from.split('\n')
  const b = to.split('\n')
  const dp: number[][] = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const rows: DiffRow[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      rows.push({ type: 'same', text: a[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      rows.push({ type: 'del', text: a[i] })
      i++
    } else {
      rows.push({ type: 'add', text: b[j] })
      j++
    }
  }
  while (i < a.length) rows.push({ type: 'del', text: a[i++] })
  while (j < b.length) rows.push({ type: 'add', text: b[j++] })
  return rows
}

// diff 状态：from=旧版本 to=新版本（add=新版本新增）
const diffFromNo = ref<number | undefined>(undefined)
const diffToNo = ref<number | undefined>(undefined)

const versionOptions = computed(() =>
  versions.value.map((v) => ({ value: v.versionNo, label: `v${v.versionNo}` })),
)

const diffFromV = computed(() => versions.value.find((v) => v.versionNo === diffFromNo.value))
const diffToV = computed(() => versions.value.find((v) => v.versionNo === diffToNo.value))

const diffRows = computed<DiffRow[]>(() =>
  diffFromV.value && diffToV.value ? buildDiff(diffFromV.value.content, diffToV.value.content) : [],
)

const diffStat = computed(() => {
  const add = diffRows.value.filter((r) => r.type === 'add').length
  const del = diffRows.value.filter((r) => r.type === 'del').length
  return { add, del }
})

function openDiff(fromV: PromptVersion, toV: PromptVersion) {
  diffFromNo.value = fromV.versionNo
  diffToNo.value = toV.versionNo
  mode.value = 'diff'
}

// 相同行折叠：连续 same ≥ FOLD_MIN 折叠中段，保留头尾各 FOLD_CTX 行上下文
const FOLD_MIN = 6
const FOLD_CTX = 2

interface RenderBlock {
  kind: 'rows' | 'fold'
  rows: DiffRow[]
  hidden: number
}

const diffBlocks = computed<RenderBlock[]>(() => {
  const rows = diffRows.value
  const out: RenderBlock[] = []
  let buf: DiffRow[] = []
  const flush = () => {
    if (buf.length) {
      out.push({ kind: 'rows', rows: buf, hidden: 0 })
      buf = []
    }
  }
  let i = 0
  while (i < rows.length) {
    if (rows[i].type !== 'same') {
      buf.push(rows[i])
      i++
      continue
    }
    let j = i
    while (j < rows.length && rows[j].type === 'same') j++
    const run = rows.slice(i, j)
    if (run.length >= FOLD_MIN) {
      buf.push(...run.slice(0, FOLD_CTX))
      flush()
      out.push({ kind: 'fold', rows: run.slice(FOLD_CTX, run.length - FOLD_CTX), hidden: run.length - FOLD_CTX * 2 })
      buf.push(...run.slice(run.length - FOLD_CTX))
      flush()
    } else {
      buf.push(...run)
    }
    i = j
  }
  flush()
  return out
})

const expandedFolds = ref(new Set<number>())

function toggleFold(bi: number) {
  const s = new Set(expandedFolds.value)
  if (s.has(bi)) s.delete(bi)
  else s.add(bi)
  expandedFolds.value = s
}

watch([diffFromNo, diffToNo], () => {
  expandedFolds.value = new Set()
})

onMounted(async () => {
  await Promise.all([loadKeys(), loadAgents()])
  // Agent 清单页跳转直达：#/admin/prompt?key=<encoded>
  const m = window.location.hash.match(/[?&]key=([^&]+)/)
  if (m) {
    const key = decodeURIComponent(m[1])
    const meta = keys.value.find((x) => x.promptKey === key)
    if (meta) selectKey(meta)
  }
})
</script>

<template>
  <div class="page-scroll prompt-page">
    <!-- 页头 -->
    <div class="page-header">
      <div>
        <span class="eyebrow">Prompt Library</span>
        <h1 class="page-title">Prompt 资产</h1>
        <p class="page-desc"><span class="num">{{ statsLine }}</span> · 版本只增不改 · 可对照可回滚</p>
      </div>
      <div class="page-actions">
        <a-button
          v-if="driftCount > 0"
          size="small"
          :type="driftOnly ? 'primary' : 'default'"
          @click="driftOnly = !driftOnly"
        >代码差异 {{ driftCount }}</a-button>
        <a-button size="small" @click="bundleOpen = true">
          <template #icon><AppstoreOutlined /></template>能力包
        </a-button>
        <a-button size="small" title="刷新" @click="loadKeys">
          <template #icon><ReloadOutlined :spin="loadingKeys" /></template>
        </a-button>
        <a-button size="small" type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>新建
        </a-button>
      </div>
    </div>

    <div class="prompt-body">
      <!-- 左栏：按域分组的 key 树（命名即分层，条目省去重复的域前缀） -->
      <aside class="key-pane">
        <a-input-search v-model:value="search" placeholder="搜索 key（如 rag-answer）" size="small" />
        <div class="key-scroll">
          <div v-if="loadingKeys" class="key-empty">加载中…</div>
          <template v-else>
            <!-- 链路卡片 → 环节胶囊 → key 末段（归类由后端集中声明） -->
            <section v-for="c in chainGroups" :key="c.chainKey" class="chain-card">
              <button class="chain-head" @click="toggleCollapse(c.chainKey)">
                <span class="chain-ic-wrap"><component :is="chainIcon(c.code)" class="chain-ic" /></span>
                <span class="chain-name">{{ c.label }}</span>
                <span class="chain-count num">{{ c.total }}</span>
                <DownOutlined class="chain-chev" :class="{ closed: collapsed.has(c.chainKey) }" />
              </button>
              <div v-if="!collapsed.has(c.chainKey)" class="chain-body">
                <template v-for="s in c.segs" :key="s.segKey">
                  <button class="seg-head" @click="toggleCollapse(s.segKey)">
                    <DownOutlined class="grp-chev" :class="{ closed: collapsed.has(s.segKey) }" />
                    <span class="seg-name">{{ s.label }}</span>
                    <span class="seg-count num">{{ s.keys.length }}</span>
                  </button>
                  <div v-if="!collapsed.has(s.segKey)" class="seg-body">
                    <button
                      v-for="k in s.keys"
                      :key="k.promptKey"
                      class="key-item"
                      :class="{ active: k.promptKey === selectedKey }"
                      :title="k.promptKey + (k.codeOnly ? '（未建档）' : ' · v' + k.currentVersion)"
                      @click="selectKey(k)"
                    >
                      <span class="key-tail">{{ tailOf(k) }}</span>
                      <span
                        v-if="k.driftedFromCode && !k.codeOnly"
                        class="drift-dot"
                        title="代码与线上内容有差异"
                      ></span>
                      <span class="key-ver num" :class="{ 'is-none': k.codeOnly }">
                        {{ k.codeOnly ? '未建档' : 'v' + k.currentVersion }}
                      </span>
                    </button>
                  </div>
                </template>
              </div>
            </section>
            <div v-if="chainGroups.length === 0" class="key-empty">无匹配 key</div>
          </template>
        </div>
      </aside>

      <!-- 右栏：内容优先工作区 -->
      <section class="work-pane">
        <!-- 空态：全站角括号 motif + 点阵台面 -->
        <div v-if="!selectedMeta" class="work-empty dot-grid">
          <div class="empty-tile">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M4.5 9.5V6.3c0-1 .8-1.8 1.8-1.8h3.2" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
              <path d="M19.5 14.5v3.2c0 1-.8 1.8-1.8 1.8h-3.2" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
              <circle cx="12" cy="12" r="2.5" fill="currentColor" />
            </svg>
          </div>
          <p class="empty-title">选择一个 prompt key</p>
          <p class="empty-hint">
            左侧按域分组 · 共 {{ keys.length }} 个资产
            <template v-if="driftCount > 0"> · {{ driftCount }} 处代码差异</template>
          </p>
        </div>

        <template v-else>
          <!-- 资产头：面包屑 → 大号路径 → 元信息 → 动作 -->
          <header class="asset-head">
            <div class="asset-title">
              <div
                v-if="selectedMeta.chainLabel"
                class="asset-crumb"
              >{{ selectedMeta.chainLabel }}<template v-if="selectedMeta.segmentLabel"> · {{ selectedMeta.segmentLabel }}</template></div>
              <div class="asset-path" :title="selectedKey ?? ''">
                <span class="path-domain">{{ selectedKey?.slice(0, (selectedKey?.indexOf('/') ?? -1) + 1) }}</span>
                <span class="path-rest">{{ selectedKey?.slice((selectedKey?.indexOf('/') ?? -1) + 1) }}</span>
              </div>
              <div class="asset-meta">
                <span v-if="roleText" class="role-badge" :class="roleText.tier">{{ roleText.badge }}</span>
                <span v-for="c in consumers" :key="c.agentType + c.role" class="ac-chip" :title="c.agentLabel + '（' + c.agentType + '）· ' + c.role">
                  {{ c.agentLabel }}<em>{{ c.role }}</em>
                </span>
                <span v-if="roleText" class="role-desc">{{ roleText.desc }}</span>
              </div>
            </div>
            <div class="asset-actions">
              <span v-if="!selectedMeta.codeOnly && currentVersion" class="ver-stamp num">
                v{{ currentVersion.versionNo }}
              </span>
              <span v-else class="ver-stamp is-none num">未建档</span>
              <a-button
                v-if="!selectedMeta.codeOnly"
                size="small"
                type="primary"
                @click="startEdit"
              >
                <template #icon><EditOutlined /></template>发新版本
              </a-button>
            </div>
          </header>

          <!-- 代码差异横幅：DB 为运行态真相，改动代码需收编发版 -->
          <div v-if="selectedMeta.driftedFromCode && !selectedMeta.codeOnly" class="drift-banner">
            <span>classpath 代码内容与线上版本不一致（DB 为运行态真相）</span>
            <a-button size="small" @click="doSyncFromCode">
              <template #icon><SyncOutlined /></template>以代码内容发新版本
            </a-button>
          </div>

          <!-- 线上创建说明：无代码文件，版本即唯一真相 -->
          <div v-else-if="selectedMeta.codeMissing" class="miss-banner">
            线上创建（无 classpath 代码文件）——版本历史即唯一真相
          </div>

          <!-- 未建档态：key 只在代码里，建档即 v1 -->
          <div v-if="selectedMeta.codeOnly" class="codeonly-card">
            <p class="co-title">该 key 只存在于代码（classpath prompts/），DB 尚未建档</p>
            <p class="co-hint">建档即以代码内容发布 v1，之后进入正常版本管理。</p>
            <a-button type="primary" size="small" @click="doSyncFromCode">
              <template #icon><SyncOutlined /></template>以代码内容建档 v1
            </a-button>
          </div>

          <template v-else>
            <!-- diff 专注态 -->
            <div v-if="mode === 'diff'" class="diff-pane">
              <div class="diff-head">
                <span class="diff-label">对比</span>
                <a-select v-model:value="diffFromNo" :options="versionOptions" size="small" class="w-xs" />
                <span class="diff-arrow">→</span>
                <a-select v-model:value="diffToNo" :options="versionOptions" size="small" class="w-xs" />
                <span v-if="diffStat" class="diff-stat num">
                  <b class="add">+{{ diffStat.add }}</b><b class="del">−{{ diffStat.del }}</b>
                </span>
                <a-button size="small" type="text" class="diff-back" @click="mode = 'read'">返回</a-button>
              </div>
              <div class="diff-body">
                <template v-for="(b, bi) in diffBlocks" :key="bi">
                  <template v-if="b.kind === 'rows'">
                    <div v-for="(row, ri) in b.rows" :key="ri" class="diff-row" :class="row.type">
                      <span class="diff-mark num">{{ row.type === 'add' ? '+' : row.type === 'del' ? '−' : '' }}</span>
                      <span class="diff-text">{{ row.text }}</span>
                    </div>
                  </template>
                  <button v-else-if="!expandedFolds.has(bi)" class="diff-fold" @click="toggleFold(bi)">
                    ⋯ {{ b.hidden }} 行未变，点击展开
                  </button>
                  <template v-else>
                    <div v-for="(row, ri) in b.rows" :key="ri" class="diff-row" :class="row.type">
                      <span class="diff-mark num">{{ row.type === 'add' ? '+' : row.type === 'del' ? '−' : '' }}</span>
                      <span class="diff-text">{{ row.text }}</span>
                    </div>
                    <button class="diff-fold" @click="toggleFold(bi)">收起 {{ b.hidden }} 行</button>
                  </template>
                </template>
              </div>
            </div>

            <!-- 发新版本专注态：左当前只读 / 右编辑 -->
            <div v-else-if="mode === 'edit'" class="edit-pane">
              <div class="edit-cols">
                <div class="edit-col">
                  <div class="edit-col-head">
                    当前 <span class="num">v{{ currentVersion?.versionNo }}</span> · 只读
                  </div>
                  <pre class="prompt-pre">{{ currentVersion?.content }}</pre>
                </div>
                <div class="edit-col">
                  <div class="edit-col-head is-edit">新版本</div>
                  <a-textarea v-model:value="editContent" class="edit-area" :spellcheck="false" />
                </div>
              </div>
              <div class="edit-foot">
                <a-input v-model:value="editNote" placeholder="变更说明（选填，写进版本历史）" class="edit-note" />
                <a-button @click="mode = 'read'">取消</a-button>
                <a-button type="primary" :loading="publishing" @click="submitNewVersion">发布新版本</a-button>
              </div>
            </div>

            <!-- 阅读态：内容 / 历史 双 tab -->
            <template v-else>
              <div class="pane-tabs">
                <button class="pane-tab" :class="{ active: readTab === 'content' }" @click="readTab = 'content'">
                  内容<span v-if="viewVersion" class="tab-sub num">v{{ viewVersion.versionNo }}</span>
                </button>
                <button class="pane-tab" :class="{ active: readTab === 'history' }" @click="readTab = 'history'">
                  历史<span class="tab-sub num">{{ versions.length }}</span>
                </button>
              </div>

              <div class="pane-body">
                <div v-if="loadingVersions" class="pane-loading">加载版本中…</div>

                <!-- 内容：默认全文可见 -->
                <template v-else-if="readTab === 'content'">
                  <div v-if="viewVersion" class="content-pane">
                    <div class="ver-bar">
                      <span class="ver-stamp sm num">v{{ viewVersion.versionNo }}</span>
                      <span v-if="viewVersion.versionNo === currentVersion?.versionNo" class="cur-pill">当前</span>
                      <a-button
                        v-else
                        size="small"
                        type="link"
                        class="back-cur"
                        @click="viewVersion = currentVersion"
                      >回到当前版本</a-button>
                      <span class="ver-bar-time num" :title="fmtTime(viewVersion.createTime)">
                        {{ fmtShort(viewVersion.createTime) }}
                      </span>
                      <span class="ver-bar-note">{{ viewVersion.changeNote || EMPTY }}</span>
                      <button class="copy-btn" title="复制内容" @click="copyContent">
                        <CopyOutlined />
                      </button>
                    </div>
                    <pre class="prompt-pre">{{ viewVersion.content }}</pre>
                  </div>
                </template>

                <!-- 历史：紧凑版本表 -->
                <template v-else>
                  <div class="ver-row ver-head-row">
                    <span>版本</span><span>时间</span><span>变更说明</span><span></span>
                  </div>
                  <div
                    v-for="(v, idx) in versions"
                    :key="v.id"
                    class="ver-row"
                    :class="{ current: idx === 0 }"
                    title="点击查看该版本内容"
                    @click="showVersion(v)"
                  >
                    <span class="cell-ver">
                      <b class="num">v{{ v.versionNo }}</b>
                      <span v-if="idx === 0" class="cur-pill">当前</span>
                    </span>
                    <span class="cell-time num" :title="fmtTime(v.createTime)">{{ fmtShort(v.createTime) }}</span>
                    <span class="cell-note">{{ v.changeNote || EMPTY }}</span>
                    <span class="cell-ops" @click.stop>
                      <a-button
                        v-if="idx < versions.length - 1"
                        type="text"
                        size="small"
                        title="与前版对比"
                        @click="openDiff(versions[idx + 1]!, v)"
                      >
                        <template #icon><DiffOutlined /></template>
                      </a-button>
                      <a-popconfirm
                        v-if="idx !== 0"
                        :title="`以 v${v.versionNo} 的内容发布为新版本？`"
                        @confirm="doRollback(v)"
                      >
                        <a-button type="text" size="small" title="回滚到此版">
                          <template #icon><RollbackOutlined /></template>
                        </a-button>
                      </a-popconfirm>
                    </span>
                  </div>
                </template>
              </div>
            </template>
          </template>
        </template>
      </section>
    </div>

    <!-- 新建 prompt 对话框：建档即 v1 -->
    <a-modal
      v-model:open="createOpen"
      title="新建 prompt"
      :confirm-loading="creating"
      ok-text="创建（建档 v1）"
      cancel-text="取消"
      @ok="submitCreate"
    >
      <div class="create-form">
        <div class="cf-field">
          <label class="cf-label">key（命名即分层：域/功能/名称）</label>
          <a-input
            v-model:value="createKey"
            class="cf-key"
            placeholder="如 agent/ops/new-step、chat/my-style"
            :status="createKeyErr ? 'error' : undefined"
            autofocus
          />
          <p v-if="createKeyErr" class="cf-err">{{ createKeyErr }}</p>
        </div>
        <div class="cf-field">
          <label class="cf-label">内容</label>
          <a-textarea v-model:value="createContent" class="cf-content" :rows="10" placeholder="prompt 正文…" />
        </div>
        <div class="cf-field">
          <label class="cf-label">变更说明（选填）</label>
          <a-input v-model:value="createNote" placeholder="如：新建 xxx 工作流的槽位抽取 prompt" />
        </div>
        <p class="cf-hint">
          线上创建的 key 无代码文件，版本历史即唯一真相；创建后可在「能力包」中组包绑定到 agent / workflow。
        </p>
      </div>
    </a-modal>

    <!-- 能力包与绑定抽屉 -->
    <PromptBundleDrawer v-model:open="bundleOpen" :keys="keys" />
  </div>
</template>

<style scoped>
/* ── 页面骨架：page-scroll 外壳（整页不滚，两栏各自内滚） ── */
.prompt-page {
  gap: 0;
}
.prompt-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
}

/* ── 左栏：key 域分组树 ── */
/* ── 左栏：链路卡片浏览器（链路卡片 → 环节胶囊 → key 末段） ── */
.key-pane {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 12px 14px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  min-height: 0;
}
.key-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 2px;
}

/* 链路卡片（flex-shrink:0——纵向 flex 滚动容器中子项不压缩，溢出才触发滚动） */
.chain-card {
  flex-shrink: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
}
.chain-head {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 12px;
  border: none;
  background: var(--color-surface-secondary);
  cursor: pointer;
  text-align: left;
}
.chain-head:hover .chain-name {
  color: var(--color-primary);
}
.chain-ic-wrap {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--color-primary-light);
  color: var(--color-primary);
  flex-shrink: 0;
}
.chain-ic {
  font-size: 13px;
}
.chain-name {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--color-ink);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.12s;
}
.chain-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
  flex-shrink: 0;
}
.chain-chev {
  font-size: 9px;
  color: var(--color-ink-tertiary);
  transition: transform 0.15s;
  flex-shrink: 0;
}
.chain-chev.closed {
  transform: rotate(-90deg);
}
.chain-body {
  padding: 6px 8px 8px 10px;
}

/* 环节胶囊行 */
.seg-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 4px 4px 4px 14px;
  margin: 5px 0 2px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
}
.seg-head:first-child {
  margin-top: 2px;
}
.seg-head:hover .seg-name {
  color: var(--color-ink);
}
.grp-chev {
  font-size: 8px;
  color: var(--color-ink-tertiary);
  transition: transform 0.15s;
  flex-shrink: 0;
}
.grp-chev.closed {
  transform: rotate(-90deg);
}
.seg-name {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-ink-secondary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 1px 10px;
  line-height: 18px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.seg-count {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--color-ink-tertiary);
  flex-shrink: 0;
}
.seg-body {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

/* key 条目：末段名 + 版本 pill（相对环节头缩进表层级） */
.key-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 6px 44px;
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  cursor: pointer;
  text-align: left;
  transition: background 0.12s;
}
.key-item:hover {
  background: var(--color-hover-bg);
}
.key-item.active {
  background: var(--color-hover-tint);
  box-shadow: inset 2.5px 0 0 var(--color-primary);
}
.key-tail {
  flex: 1;
  min-width: 0;
  font-family: var(--font-display);
  font-size: 12px;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.key-item.active .key-tail {
  font-weight: 700;
}
.drift-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-signal);
  flex-shrink: 0;
}
.key-ver {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 16px;
}
.key-ver.is-none {
  color: var(--color-warning-ink);
  background: var(--color-signal-bg);
}
.key-empty {
  padding: 24px 0;
  text-align: center;
  color: var(--color-ink-tertiary);
  font-size: 12px;
}

/* ── 右栏：工作区 ── */
.work-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  min-height: 0;
  overflow: hidden;
}

/* 空态 */
.work-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.empty-tile {
  width: 72px;
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-xl);
  background: var(--color-primary-light);
  color: var(--color-primary);
  margin-bottom: 8px;
}
.empty-tile svg {
  width: 34px;
  height: 34px;
}
.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
}
.empty-hint {
  margin: 0;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

/* 资产头：面包屑 → 大号路径 → 元信息一行（角色/引用方） */
.asset-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 20px 12px;
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
  flex-wrap: wrap;
}
.asset-title {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.asset-crumb {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  letter-spacing: 0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.asset-path {
  font-family: var(--font-display);
  font-size: 16px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.path-domain {
  color: var(--color-ink-tertiary);
}
.path-rest {
  color: var(--color-ink);
  font-weight: 700;
}
.asset-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}
.role-badge {
  font-size: 10.5px;
  font-weight: 500;
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
  flex-shrink: 0;
}
.role-badge.base {
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.role-badge.feature {
  color: var(--color-ink-secondary);
  background: var(--color-surface-secondary);
}
.role-badge.offline {
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
}
.ac-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 4px;
  font-size: 11px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: 999px;
  padding: 0 9px;
  line-height: 18px;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
}
.ac-chip em {
  font-style: normal;
  font-size: 10px;
  color: var(--color-ink-secondary);
}
.role-desc {
  font-size: 11.5px;
  color: var(--color-ink-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.asset-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

/* 版本戳：本页签名元素（git 资产语言） */
.ver-stamp {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-md);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.01em;
}
.ver-stamp.sm {
  font-size: 12px;
  padding: 1px 8px;
}
.ver-stamp.is-none {
  background: var(--color-surface-secondary);
  color: var(--color-ink-tertiary);
}

/* 差异横幅 */
.drift-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 18px;
  background: var(--color-signal-bg);
  color: var(--color-warning-ink);
  font-size: 12.5px;
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
  flex-wrap: wrap;
}
.drift-banner .ant-btn {
  margin-left: auto;
  flex-shrink: 0;
}

/* 线上创建说明条（中性） */
.miss-banner {
  padding: 7px 18px;
  background: var(--color-surface-secondary);
  color: var(--color-ink-tertiary);
  font-size: 12px;
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}

/* 新建对话框 */
.create-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.cf-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cf-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--color-ink-secondary);
}
.cf-key {
  font-family: var(--font-display);
}
.cf-err {
  margin: 0;
  font-size: 12px;
  color: var(--color-danger);
}
.cf-content {
  font-family: var(--font-display);
  font-size: 12.5px;
}
.cf-hint {
  margin: 0;
  font-size: 12px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  padding: 8px 12px;
}

/* 未建档卡 */
.codeonly-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 32px 20px;
}
.co-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--color-ink);
}
.co-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

/* ── 阅读态双 tab ── */
.pane-tabs {
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 0 18px;
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}
.pane-tab {
  border: none;
  background: none;
  padding: 9px 12px;
  font-size: 13px;
  color: var(--color-ink-secondary);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: color 0.15s, border-color 0.15s;
}
.pane-tab:hover {
  color: var(--color-ink);
}
.pane-tab.active {
  color: var(--color-ink);
  font-weight: 600;
  border-bottom-color: var(--color-primary);
}
.tab-sub {
  font-size: 10.5px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 17px;
}
.pane-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 18px 16px;
}
.pane-loading {
  padding: 32px 0;
  text-align: center;
  color: var(--color-ink-tertiary);
  font-size: 12px;
}

/* 内容 tab */
.content-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.ver-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 10px;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--color-border-light);
  font-size: 12px;
  flex-shrink: 0;
}
.cur-pill {
  font-size: 10.5px;
  font-weight: 500;
  color: var(--color-success);
  background: var(--color-success-bg);
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
  flex-shrink: 0;
}
.back-cur {
  padding: 0;
  font-size: 12px;
  height: auto;
}
.ver-bar-time {
  color: var(--color-ink-tertiary);
  font-size: 11px;
  flex-shrink: 0;
}
.ver-bar-note {
  color: var(--color-ink-secondary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.copy-btn {
  margin-left: auto;
  border: none;
  background: none;
  color: var(--color-ink-tertiary);
  cursor: pointer;
  font-size: 13px;
  padding: 2px 4px;
  border-radius: var(--radius-sm);
  flex-shrink: 0;
}
.copy-btn:hover {
  color: var(--color-primary);
  background: var(--color-hover-tint);
}

/* prompt 正文：JetBrains Mono */
.prompt-pre {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 12px 14px;
  font-family: var(--font-display);
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--color-ink);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  white-space: pre-wrap;
  word-break: break-word;
  overflow-y: auto;
}

/* 历史 tab：紧凑版本表 */
.ver-row {
  display: grid;
  grid-template-columns: 96px 108px 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 7px 10px;
  font-size: 12.5px;
  border-bottom: 1px solid var(--color-border-light);
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.ver-row:hover {
  background: var(--color-hover-bg);
}
.ver-head-row {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  cursor: default;
  border-bottom-color: var(--color-border);
}
.ver-head-row:hover {
  background: none;
}
.ver-row.current .cell-ver b {
  color: var(--color-primary);
}
.cell-ver {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.cell-ver b {
  font-size: 12.5px;
  font-weight: 700;
}
.cell-time {
  color: var(--color-ink-tertiary);
  font-size: 11px;
}
.cell-note {
  color: var(--color-ink-secondary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell-ops {
  display: inline-flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.12s;
}
.ver-row:hover .cell-ops {
  opacity: 1;
}

/* ── diff 专注态 ── */
.diff-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.diff-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}
.diff-label {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.diff-arrow {
  color: var(--color-ink-tertiary);
}
.diff-stat {
  margin-left: 8px;
  font-size: 12px;
}
.diff-stat b {
  font-weight: 700;
}
.diff-stat .add {
  color: var(--color-success);
}
.diff-stat .del {
  color: var(--color-warning-ink);
  margin-left: 6px;
}
.diff-back {
  margin-left: auto;
}
.diff-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 0 14px;
  font-family: var(--font-display);
  font-size: 12px;
  line-height: 1.6;
}
.diff-row {
  display: flex;
  padding: 0 14px;
}
.diff-row.add {
  background: var(--color-success-bg);
}
.diff-row.del {
  background: var(--color-signal-bg);
}
.diff-mark {
  width: 20px;
  flex-shrink: 0;
  user-select: none;
  color: var(--color-ink-tertiary);
}
.diff-row.add .diff-mark {
  color: var(--color-success);
  font-weight: 700;
}
.diff-row.del .diff-mark {
  color: var(--color-warning-ink);
  font-weight: 700;
}
.diff-text {
  white-space: pre-wrap;
  word-break: break-all;
  min-width: 0;
}
.diff-fold {
  display: block;
  width: 100%;
  border: none;
  background: none;
  padding: 2px 14px;
  text-align: center;
  font-size: 11px;
  color: var(--color-ink-tertiary);
  cursor: pointer;
}
.diff-fold:hover {
  color: var(--color-primary);
  background: var(--color-hover-tint);
}

/* ── 发新版本专注态 ── */
.edit-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.edit-cols {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 12px 18px;
}
.edit-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}
.edit-col-head {
  font-size: 12px;
  color: var(--color-ink-secondary);
  margin-bottom: 8px;
  flex-shrink: 0;
}
.edit-col-head.is-edit {
  color: var(--color-primary);
  font-weight: 600;
}
.edit-area {
  flex: 1;
  min-height: 0;
  font-family: var(--font-display);
  font-size: 12.5px;
  line-height: 1.65;
  resize: none;
}
.edit-foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-top: 1px solid var(--color-border-light);
  flex-shrink: 0;
}
.edit-note {
  flex: 1;
  min-width: 0;
}

/* ── 响应式：窄屏左栏收窄，再窄上下堆叠 ── */
@media (max-width: 1280px) {
  .key-pane {
    width: 248px;
  }
}
@media (max-width: 960px) {
  .prompt-body {
    flex-direction: column;
  }
  .key-pane {
    width: auto;
    max-height: 240px;
  }
  .edit-cols {
    grid-template-columns: 1fr;
  }
  .edit-col:first-child {
    max-height: 34%;
    flex-shrink: 0;
  }
}
</style>
