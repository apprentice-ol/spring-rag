<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { CaretRightOutlined } from '@ant-design/icons-vue'
import type { AgentTrace, AgentStep } from '../api/chat'
import { fmtLatency } from '../utils/format'
import TraceHitsTable from './TraceHitsTable.vue'
import {
  analyzeTrace, argsLine, clip, hitCountOf, isDiagnosisTrace, kindLabel, kindOf, metaOf,
  nodeLabel, parseToolCall, slotLabel, translateDecision,
  type ChunkRow, type StepKind, type TraceNotice, type TraceToolCall,
} from '../utils/traceAnalysis'

/**
 * 执行轨迹树（对话轨迹抽屉 / 轨迹管理页 / Live 对照共用）。
 *
 * 信息范式对齐 agent-framework 仓的 TraceDrawer —— 轨迹是一串「动作」，不是节点流水账：
 *   行首  类型色点（LLM 紫 / 工具蓝 / 条件琥珀 / 人工绿 / 失败红；悬停显示步号）
 *   行头  中文名称 + 类型徽标 + 命中/裁决/迭代上限徽标 + 右对齐耗时与 token
 *   步内  in: 输入 / out: 产物（长文可展开）/ 写入槽位（芯片，悬停看全值）
 * 诊断轨迹按阶段重组（入口问齐 → 定位 → 纠正 → 校验 → 终态）：阶段头是长链路的刻度
 * （名称 / 环内轮次 / 段耗时），顶部占比条让「时间花在哪段」一眼可见，异常置顶。
 *
 * 兼容两代数据：新内核步带 detail 元数据（见 StepMeta）；旧数据（含旧 ReAct 的
 * retrieve/grade/rerank detail 表格）按 action 映射退化渲染。
 */
const props = defineProps<{
  trace: AgentTrace | null
  /** 本轮提问（extract_slots 等首步的输入口径；缺省时该步不显示提问） */
  question?: string
  /** 回答引用（命中表行的「被引用」标注；ref 与正文 [N] 角标同源） */
  citations?: { ref: number }[]
}>()

/* ============ 类型色板（与徽标同源） ============ */

const KIND_COLOR: Record<StepKind, string> = {
  llm: 'var(--color-primary, #6c5ce7)',
  tool: '#2f7ff5',
  condition: '#d4880f',
  human: '#389e0d',
  parallel: '#13c2c2',
  sub: '#722ed1',
  fail: '#cf1322',
  plain: '#8c8c8c',
}
function dotColor(step: AgentStep): string {
  return KIND_COLOR[kindOf(step)]
}

/* ============ 条目构建（tool / decision / step 三类） ============ */

interface TraceEntry {
  key: string
  index: number
  kind: 'tool' | 'decision' | StepKind
  /** 通用步本体（step 类条目用） */
  step?: AgentStep
  /** 工具调用（tool 类条目用，think+act 合并） */
  call?: TraceToolCall
  /** 决策动作句（decision 类条目用） */
  text?: string
  label: string
  input: string
  output: string
  /** 检索命中明细（数据来自工具 data 槽位；渲染为命中表） */
  chunks?: ChunkRow[]
  writes: { key: string; label: string; title: string }[]
  verdict?: string
  hitsBadge?: string
  loopBreak?: boolean
  failed?: boolean
  error?: string
  model: string
  durationMs: number
  thinkDurationMs?: number
  tokens: number
  slow: boolean
  note: string
}

/** 框架内部记账槽位：执行 plumbing，不进「写入」行。 */
const INTERNAL_SLOTS = new Set(['llm_calls', 'stage_output', 'has_calls', 'slot_extract_raw', 'ver_out', 'retries'])

function writeOf(key: string, value: unknown) {
  let text = ''
  if (typeof value === 'string') {
    text = value
  } else if (Array.isArray(value)) {
    text = `${value.length} 项`
  } else if (value && typeof value === 'object') {
    const chunks = (value as { chunks?: unknown[] }).chunks
    text = Array.isArray(chunks) ? `命中 ${chunks.length} 条` : ''
  } else if (value !== null && value !== undefined) {
    text = String(value)
  }
  const preview = text.length > 160 ? `${text.slice(0, 160)}…` : text
  return { key, label: slotLabel(key), title: preview ? `${key} = ${preview}` : key }
}

function writesOf(step: AgentStep) {
  const meta = metaOf(step)
  return Object.entries(meta?.slotWrites ?? {})
    .filter(([key, value]) => !INTERNAL_SLOTS.has(key)
      && !/_scratchpad$/.test(key) && key !== 'scratchpad'
      && value !== null && value !== undefined && String(value).trim() !== '')
    .map(([key, value]) => writeOf(key, value))
}

function tokensOf(step: AgentStep): number {
  const meta = metaOf(step)
  return (meta?.promptTokens ?? 0) + (meta?.completionTokens ?? 0)
}

/** 路由补充说明：只留「本来会走别的路」的额外信息（被拒动态选路 / 循环中断）。 */
function noteOf(step: AgentStep): string {
  const meta = metaOf(step)
  const detail = meta?.routeDetail ?? ''
  const echo = meta?.routeTo ? `${step.action}->${meta.routeTo}` : ''
  return detail && detail !== echo ? detail : ''
}

/** 产物与气泡正文重复的终态节点：不再铺开，只留耗时与读数。 */
const ANSWER_NODES = new Set(['answer', 'finish', 'conclude', 'chitchat', 'kb_done'])

/** think 的决策原文（answer/ask_user/escalate）——识别「act 只是回写决策」的回显步。 */
function decisionRaw(output?: string): string {
  if (typeof output !== 'string' || !output.trimStart().startsWith('{')) {
    return ''
  }
  try {
    const parsed = JSON.parse(output.trim()) as Record<string, unknown>
    return String(parsed.answer ?? parsed.ask_user ?? parsed.escalate ?? '')
  } catch {
    return ''
  }
}

function retrievalOf(step: AgentStep | undefined): { query: string; chunks: ChunkRow[] } | null {
  const writes = step ? metaOf(step)?.slotWrites : null
  if (!writes) {
    return null
  }
  const data = writes['kb_chunks_data']
  if (data && typeof data === 'object' && Array.isArray((data as { chunks?: unknown }).chunks)) {
    const wrapper = data as { query?: unknown; chunks: unknown[] }
    return { query: typeof wrapper.query === 'string' ? wrapper.query : '', chunks: wrapper.chunks as ChunkRow[] }
  }
  const reactChunks = writes['react_tool_chunks'] ?? writes['tool_chunks']
  if (Array.isArray(reactChunks) && reactChunks.length > 0) {
    return { query: '', chunks: reactChunks as ChunkRow[] }
  }
  return null
}

/** 被正文引用的 ref 集合（命中表「被引用」标注的判定源）。 */
const citedRefs = computed(() => new Set((props.citations ?? []).map((c) => c.ref)))

/** 检索观测文本 → 一行归纳（「命中 N 条」），不再铺 markdown 原文。 */
function retrievalSummary(text: string | undefined, chunks: ChunkRow[]): string {
  const fromText = hitCountOf(text)
  const count = fromText ?? chunks.length
  return count > 0 ? `命中 ${count} 条` : '无命中'
}

/* ============ 槽位回放 + 每节点输入口径（对齐 agent-framework TraceDrawer） ============ */

/**
 * 逐步回放 slotWrites，得到每一步执行前的槽位快照。
 * 轨迹只记录「这一步写了什么」；按步序回放即可还原每步开始时的共享状态——
 * 各步的输入文案由此而来（回放而非猜测，口径不确定的节点宁可不给）。
 */
const slotSnapshots = computed(() => {
  const snapshots = new Map<number, Map<string, unknown>>()
  const state = new Map<string, unknown>()
  steps.value.forEach((step) => {
    snapshots.set(step.stepIndex, new Map(state))
    Object.entries(metaOf(step)?.slotWrites ?? {}).forEach(([key, value]) => state.set(key, value))
  })
  return snapshots
})

/** 槽位里的命中条数（kb_chunks_data = {chunks:[…]}）；-1 = 该槽位还没写。 */
function chunkCountOf(data: unknown): number {
  if (!data || typeof data !== 'object') {
    return -1
  }
  const chunks = (data as { chunks?: unknown }).chunks
  return Array.isArray(chunks) ? chunks.length : -1
}

/**
 * 各步「输入」的展示口径（引擎轨迹无独立输入字段，按节点链路语义从槽位快照取值）：
 * 诊断链读缺项数 / 用户补充 / 阶段产出 / 过程记录；检索链读归一化查询 / 改写查询 / 命中上下文。
 *
 * 与 agent-framework 的 TraceDrawer.inputOf 同源：槽位快照由 slotWrites 逐步回放而来，
 * 所以这是「重放」不是「猜测」——口径不确定的节点宁可不给，也不写一句像模像样的假话。
 */
function inputOf(step: AgentStep, slots: Map<string, unknown>): string {
  const slot = (name: string): string => {
    const value = slots.get(name)
    return value === null || value === undefined ? '' : String(value)
  }
  const prefix = (step.action.match(/^(inv|res|ver|react)_/)?.[1] ?? '') + '_'
  switch (step.action) {
    case 'extract_slots':
      return slot('user_clarify')
        ? `用户补充「${clip(slot('user_clarify'))}」`
        : (props.question ? `提问「${clip(props.question)}」` : '')
    case 'slots_gate':
    case 'auto_gate':
      return slot('missing_count') !== '' ? `缺项数 = ${slot('missing_count')}` : ''
    case 'auto_resolve':
      return slot('missing_count') !== '' ? `缺项数 = ${slot('missing_count')}（规则 → LLM 推断 → 日志反查）` : ''
    case 'collect_slots':
      return slot('missing_count') !== '' ? `待补 ${slot('missing_count')} 项` : ''
    case 'slots_regate': {
      const parts: string[] = []
      if (slot('user_clarify')) {
        parts.push(`用户补充「${clip(slot('user_clarify'))}」`)
      }
      if (slot('missing_count') !== '') {
        parts.push(`缺项数 = ${slot('missing_count')}`)
      }
      return parts.join(' + ')
    }
    case 'replan_1':
    case 'replan_2':
      return slot(`${prefix}stage_output`) ? `阶段产出（${slot(`${prefix}stage_output`).length} 字）` : ''
    case 'conclude':
    case 'escalate_node': {
      const source = ['ver_stage_output', 'res_stage_output', 'inv_stage_output']
        .map((name) => slot(name)).find((value) => value !== '')
      return source ? `阶段产出（${source.length} 字）` : ''
    }
    // ---------- 检索链（知识问答图的查询理解链 + 反思环）----------
    case 'kb_normalize':
      // 链路第一步：输入就是本轮提问原文
      return props.question ? `提问「${clip(props.question)}」` : ''
    case 'kb_classify':
      // 分类吃的是归一化结果（上一步刚写过，快照里就是它）
      return slot('normalized_query') ? `归一化查询「${clip(slot('normalized_query'))}」` : ''
    case 'kb_route': {
      const parts: string[] = []
      if (slot('intent')) {
        parts.push(`意图 = ${slot('intent')}`)
      }
      if (slot('intent_confidence')) {
        parts.push(`置信度 ${slot('intent_confidence')}`)
      }
      return parts.join(' · ')
    }
    case 'kb_route_gate': {
      const parts: string[] = []
      if (slot('route_target')) {
        parts.push(`路由目标 = ${slot('route_target')}`)
      }
      if (slot('needs_retrieval')) {
        parts.push(`需要检索 = ${slot('needs_retrieval')}`)
      }
      return parts.join(' · ')
    }
    case 'kb_rewrite': {
      // 首轮读归一化查询；反思轮读上一轮查询（rewrite_round 在步前快照里仍是旧值）
      const round = Number(slot('rewrite_round') || 0)
      const parts: string[] = []
      if (round > 0) {
        if (slot('rewritten_query')) {
          parts.push(`上一轮查询「${clip(slot('rewritten_query'))}」（不充分，扩词重试）`)
        }
      } else if (slot('normalized_query')) {
        parts.push(`归一化查询「${clip(slot('normalized_query'))}」`)
      }
      if (slot('user_clarify')) {
        parts.push(`用户补充「${clip(slot('user_clarify'))}」`)
      }
      return parts.join(' + ')
    }
    case 'kb_retrieve':
      // 工具参数就是改写查询（工作流里声明为 ${slots.rewritten_query}）
      return slot('rewritten_query') ? `{query = ${slot('rewritten_query')}}` : ''
    case 'kb_critique': {
      const count = chunkCountOf(slots.get('kb_chunks_data'))
      return count >= 0 ? `检索上下文（命中 ${count} 条）` : ''
    }
    case 'kb_done': {
      const count = chunkCountOf(slots.get('kb_chunks_data'))
      return count >= 0 ? `命中 ${count} 条` : ''
    }
    case 'kb_shortcircuit':
      return slot('intent') ? `意图 = ${slot('intent')}` : ''
    case 'answer': {
      const parts: string[] = []
      if (slot('rewritten_query')) {
        parts.push(`提问「${clip(slot('rewritten_query'))}」`)
      }
      const count = chunkCountOf(slots.get('kb_chunks_data'))
      if (count >= 0) {
        parts.push(`资料 ${count} 条`)
      }
      return parts.join(' + ')
    }
    default:
      if (/_think$/.test(step.action)) {
        return slot(`${prefix}scratchpad`) ? `过程记录（${slot(`${prefix}scratchpad`).length} 字）` : ''
      }
      if (/_act$/.test(step.action)) {
        const raw = slot(`${prefix}out`)
        if (!raw) {
          return ''
        }
        return `推理产出「${clip(translateDecision(raw) ?? raw)}」`
      }
      return ''
  }
}

/** 门禁 / 判定步的输出：给一行结论（通过 / 缺 N 项转问齐），而不是空。 */
function gateOutputOf(step: AgentStep, slots: Map<string, unknown>): string {
  if (!/(_gate|_regate|_decide)$/.test(step.action)) {
    return ''
  }
  // 知识问答图的路由闸门：条件节点不产出内容，把去向写出来——空着的判定行比没有还费解
  if (step.action === 'kb_route_gate') {
    const to = metaOf(step)?.routeTo
    return to ? `→ ${nodeLabel(to)}` : ''
  }
  if (/_decide$/.test(step.action)) {
    const hasCalls = slots.get(`${step.action.replace(/_decide$/, '')}_has_calls`)
    return '继续下一轮'
  }
  const missing = slots.get('missing_count')
  if (missing === undefined || missing === null) {
    return ''
  }
  const count = Number(missing)
  return Number.isFinite(count) && count > 0 ? `缺 ${count} 项` : '通过'
}

/** 通用步 → 条目（slots = 该步执行前的槽位快照，来自 slotSnapshots 回放）。 */
function entryOfStep(step: AgentStep, slots: Map<string, unknown> = new Map()): TraceEntry {
  const meta = metaOf(step)
  const kind = kindOf(step)
  const isDecisionNode = /_think$/.test(step.action) || step.action === 'react_think'
  const decision = isDecisionNode ? translateDecision(step.outputSummary) : null
  const verdictNode = /^replan/.test(step.action)
    && ['continue', 'adjust', 'escalate'].includes((step.outputSummary ?? '').trim())
  // 检索步：观测文本折叠为一行归纳，明细走命中表（数据来自工具 data 槽位）
  const retrieval = retrievalOf(step)
  const isRetrievalStep = retrieval !== null || step.action === 'kb_retrieve' || step.action === 'retrieve'
  let output = decision ? '' : (ANSWER_NODES.has(step.action) ? '' : step.outputSummary ?? '')
  if (!output) {
    output = gateOutputOf(step, slots)
  }
  const input = retrieval?.query || inputOf(step, slots) || (step.inputSummary ?? '')
  return {
    key: `step-${step.stepIndex}`,
    index: step.stepIndex,
    kind,
    step,
    label: nodeLabel(step.action),
    input,
    output: isRetrievalStep && retrieval ? retrievalSummary(step.outputSummary, retrieval.chunks) : output,
    chunks: isRetrievalStep ? retrieval?.chunks ?? [] : [],
    writes: isRetrievalStep ? [] : writesOf(step),
    verdict: verdictNode ? (step.outputSummary ?? '').trim() : undefined,
    hitsBadge: isRetrievalStep && retrieval ? retrievalSummary(step.outputSummary, retrieval.chunks) : undefined,
    loopBreak: /max_iterations/.test(meta?.routeDetail ?? ''),
    failed: meta?.status === 'FAILED',
    error: meta?.error,
    model: meta?.model ?? '',
    durationMs: step.latencyMs ?? 0,
    tokens: tokensOf(step),
    slow: (step.latencyMs ?? 0) >= 800,
    note: noteOf(step),
  }
}

/** 一段 steps → 条目列表：think+act 合并工具条目 / 决策翻译 / 回显合并 / _decide 折叠。 */
function entriesOf(steps: AgentStep[], toolCalls: TraceToolCall[]): TraceEntry[] {
  const toolByThink = new Map<number, TraceToolCall>()
  const toolByAct = new Map<number, TraceToolCall>()
  toolCalls.forEach((call) => {
    toolByThink.set(call.thinkIndex, call)
    if (call.actIndex >= 0) {
      toolByAct.set(call.actIndex, call)
    }
  })

  const byIndex = new Map<number, TraceEntry>()
  const echoActs = new Set<number>()
  steps.forEach((step, position) => {
    if (!/_think$/.test(step.action)) {
      return
    }
    const call = toolByThink.get(step.stepIndex)
    if (call) {
      const hits = hitCountOf(call.result)
      // 检索类工具：观测文本折叠为一行归纳，明细走命中表（act 步 data 槽位）
      const actStep = steps.find((s) => s.stepIndex === call.actIndex)
      const retrieval = call.tool === 'retrieve_knowledge' ? retrievalOf(actStep) : null
      byIndex.set(step.stepIndex, {
        key: `tool-${step.stepIndex}`,
        index: step.stepIndex,
        kind: 'tool',
        call,
        label: call.tool,
        input: retrieval?.query || argsLine(call.args),
        output: retrieval ? retrievalSummary(call.result, retrieval.chunks) : call.result,
        chunks: retrieval?.chunks ?? [],
        writes: [],
        hitsBadge: retrieval
          ? retrievalSummary(call.result, retrieval.chunks)
          : (hits === null ? undefined : (hits === 0 ? '无命中' : `命中 ${hits}`)),
        failed: call.ok === false,
        error: call.error || undefined,
        model: call.model,
        durationMs: call.durationMs,
        thinkDurationMs: call.thinkDurationMs,
        tokens: 0,
        slow: call.durationMs >= 800 || call.thinkDurationMs >= 800,
        note: '',
      })
      return
    }
    const decision = translateDecision(step.outputSummary)
    if (!decision) {
      return
    }
    const raw = decisionRaw(step.outputSummary)
    const act = steps.slice(position + 1).find((next) => /_act$/.test(next.action))
    if (act && raw && (act.outputSummary ?? '').trim() === raw.trim()) {
      echoActs.add(act.stepIndex)
    }
    const meta = metaOf(step)
    byIndex.set(step.stepIndex, {
      key: `decision-${step.stepIndex}`,
      index: step.stepIndex,
      kind: 'llm',
      label: nodeLabel(step.action),
      input: inputOf(step, slotSnapshots.value.get(step.stepIndex) ?? new Map()) || (step.inputSummary ?? ''),
      output: decision,
      writes: [],
      model: meta?.model ?? '',
      durationMs: step.latencyMs ?? 0,
      tokens: tokensOf(step),
      slow: (step.latencyMs ?? 0) >= 800,
      note: '',
    })
  })

  return steps
    .filter((step) => !/_decide$/.test(step.action))
    .filter((step) => !toolByAct.has(step.stepIndex) && !echoActs.has(step.stepIndex))
    .map((step) => byIndex.get(step.stepIndex)
      ?? entryOfStep(step, slotSnapshots.value.get(step.stepIndex) ?? new Map()))
}

/* ============ 诊断阶段分组 ============ */

const steps = computed<AgentStep[]>(() => props.trace?.steps ?? [])
const analysis = computed(() => (steps.value.length ? analyzeTrace(steps.value) : null))
const grouped = computed(() => isDiagnosisTrace(steps.value))

interface PhaseGroup {
  key: string
  label: string
  iterations: number
  durationMs: number
  notices: TraceNotice[]
  entries: TraceEntry[]
}

const groups = computed<PhaseGroup[]>(() => {
  const result = analysis.value
  if (!grouped.value || !result) {
    return []
  }
  return result.phases.map((phase) => ({
    key: phase.key,
    label: phase.label,
    iterations: phase.iterations,
    durationMs: phase.durationMs,
    notices: phase.notices,
    entries: entriesOf(phase.steps, result.toolCalls),
  }))
})

const flatEntries = computed<TraceEntry[]>(() => entriesOf(steps.value, analysis.value?.toolCalls ?? []))

/** 阶段耗时占比条：条的长短 = 该段占总耗时比例，热点一眼可见。 */
const phaseShares = computed(() => {
  const valid = groups.value.filter((g) => g.durationMs > 0)
  const total = valid.reduce((sum, g) => sum + g.durationMs, 0)
  if (total <= 0 || valid.length < 2) {
    return []
  }
  return valid
})

/* ============ 工具栏：搜索 / 类型过滤 / 一键展开 / 复制 ============ */

const query = ref('')
const kindFilter = ref<string>('all')
const FILTERS = [
  { value: 'all', label: '全部' },
  { value: 'llm', label: 'LLM' },
  { value: 'tool', label: '工具' },
  { value: 'condition', label: '条件' },
  { value: 'human', label: '人工' },
  { value: 'fail', label: '失败' },
]

const needle = computed(() => query.value.trim().toLowerCase())
function hitByQuery(parts: (string | undefined)[]): boolean {
  if (!needle.value) {
    return true
  }
  return parts.filter(Boolean).join(' ').toLowerCase().includes(needle.value)
}

function entryKindOf(entry: TraceEntry): string {
  if (entry.kind === 'tool') {
    return 'tool'
  }
  if (entry.kind === 'decision' || entry.kind === 'llm') {
    return 'llm'
  }
  if (entry.kind === 'fail') {
    return 'fail'
  }
  return entry.kind
}

function matchEntry(entry: TraceEntry): boolean {
  if (kindFilter.value !== 'all' && entryKindOf(entry) !== kindFilter.value) {
    return false
  }
  return hitByQuery([
    entry.label, kindLabel(entry.kind as StepKind), entry.input, entry.output, entry.note,
    entry.error, entry.hitsBadge, entry.verdict,
    ...entry.writes.map((w) => `${w.label} ${w.title}`),
  ])
}

const visibleGroups = computed(() => groups.value
  .map((group) => ({ ...group, entries: group.entries.filter(matchEntry) }))
  .filter((group) => group.entries.length > 0))
const visibleFlat = computed(() => flatEntries.value.filter(matchEntry))
const filtering = computed(() => Boolean(needle.value) || kindFilter.value !== 'all')
const displaySteps = computed(() => (grouped.value
  ? visibleGroups.value.reduce((sum, g) => sum + g.entries.length, 0)
  : visibleFlat.value.length))
const totalSteps = computed(() => (grouped.value
  ? groups.value.reduce((sum, g) => sum + g.entries.length, 0)
  : flatEntries.value.length))

/* ============ 展开 / 折叠 ============ */

const PREVIEW_CHARS = 220
const expandedKeys = ref<Set<string>>(new Set())
const collapsedPhases = ref<Set<string>>(new Set())

watch(() => props.trace, () => {
  expandedKeys.value = new Set()
  collapsedPhases.value = new Set()
})

function toggleExpanded(key: string) {
  const next = new Set(expandedKeys.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expandedKeys.value = next
}
function isExpanded(key: string): boolean {
  return expandedKeys.value.has(key)
}
function hasMore(text?: string): boolean {
  return typeof text === 'string' && text.length > PREVIEW_CHARS
}
function bodyOf(text: string | undefined, key: string): string {
  const value = typeof text === 'string' ? text : ''
  if (isExpanded(key) || value.length <= PREVIEW_CHARS) {
    return value
  }
  return `${value.slice(0, PREVIEW_CHARS)}…`
}

function togglePhase(key: string) {
  const next = new Set(collapsedPhases.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  collapsedPhases.value = next
}
function isPhaseCollapsed(key: string): boolean {
  return collapsedPhases.value.has(key)
}

const visibleKeys = computed(() => (grouped.value
  ? visibleGroups.value.flatMap((g) => g.entries.map((e) => e.key))
  : visibleFlat.value.map((e) => e.key)))
const allExpanded = computed(() => visibleKeys.value.length > 0
  && visibleKeys.value.every((key) => expandedKeys.value.has(key)))
function toggleExpandAll() {
  expandedKeys.value = allExpanded.value ? new Set() : new Set(visibleKeys.value)
}

/* ============ token / 读数 ============ */

function tokenTitle(entry: TraceEntry): string {
  const meta = entry.step ? metaOf(entry.step) : null
  if (!meta?.promptTokens && !meta?.completionTokens) {
    return ''
  }
  return `输入 ${meta?.promptTokens ?? 0} · 输出 ${meta?.completionTokens ?? 0}`
}

/* ============ 复制导出（Markdown，导出当前可见视图） ============ */

const copyState = ref<'idle' | 'ok' | 'fail'>('idle')
const COPY_LABELS = { idle: '复制', ok: '已复制', fail: '复制失败' }

function flashCopy(state: 'ok' | 'fail') {
  copyState.value = state
  setTimeout(() => {
    copyState.value = 'idle'
  }, 1600)
}

async function writeClipboard(text: string) {
  try {
    if (!navigator.clipboard?.writeText) {
      throw new Error('clipboard api unavailable')
    }
    await navigator.clipboard.writeText(text)
    flashCopy('ok')
  } catch {
    flashCopy(fallbackCopy(text) ? 'ok' : 'fail')
  }
}

/** 非安全上下文退回 execCommand（部署环境 http + IP 访问时 clipboard 不可用）。 */
function fallbackCopy(text: string): boolean {
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  document.body.appendChild(area)
  area.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(area)
  return ok
}

function entryLines(entry: TraceEntry): string[] {
  if (entry.kind === 'tool' && entry.call) {
    const lines = [`- **${entry.call.tool}** (工具) · think ${fmtLatency(entry.call.thinkDurationMs)}`
      + ` · exec ${fmtLatency(entry.call.durationMs)}`]
    lines.push(`  - in: ${argsLine(entry.call.args)}`)
    if (entry.call.result) {
      lines.push(`  - out: ${bodyOf(entry.call.result, entry.key)}`)
    }
    return lines
  }
  const lines = [`- **${entry.label}**${entry.kind !== 'plain' ? ` (${kindLabel(entry.kind as StepKind)})` : ''}`
    + ` · ${fmtLatency(entry.durationMs)}`]
  if (entry.input) {
    lines.push(`  - in: ${entry.input}`)
  }
  if (entry.output) {
    lines.push(`  - out: ${bodyOf(entry.output, entry.key)}`)
  }
  if (entry.writes.length) {
    lines.push(`  - 写入槽位: ${entry.writes.map((w) => w.label).join('、')}`)
  }
  if (entry.error) {
    lines.push(`  - 错误: ${entry.error}`)
  }
  return lines
}

function copyTrace() {
  const trace = props.trace
  if (!trace) {
    return
  }
  const meta = [trace.paradigm, `${displaySteps.value} 步`]
  if (trace.llmCallCount) {
    meta.push(`LLM ×${trace.llmCallCount}`)
  }
  if (trace.totalLatencyMs) {
    meta.push(`用时 ${fmtLatency(trace.totalLatencyMs)}`)
  }
  const body = grouped.value
    ? visibleGroups.value.flatMap((g) => [`## ${g.label}`, '', ...g.entries.flatMap(entryLines)])
    : visibleFlat.value.flatMap(entryLines)
  writeClipboard(['# 执行轨迹', '', meta.join(' · '), '', ...body, ''].join('\n'))
}

/* ============ 旧 detail（Retrieve/Grade/Rerank 表格）渲染保留 ============ */

function isRetrieve(d: unknown): d is { query: string; chunks: { ref: number; score: number; originalScore: number; docName: string; channel: string; preview: string }[] } {
  return !!d && typeof (d as { query?: unknown }).query === 'string' && Array.isArray((d as { chunks?: unknown }).chunks)
}
function isGrade(d: unknown): d is { verdict: string; relevant: number; total: number; avgScore: number; grades: { ref: number; score: number; relevant: boolean; reason: string }[] } {
  return !!d && Array.isArray((d as { grades?: unknown }).grades)
}
function isRerank(d: unknown): d is { topN: number; before: number[]; after: { ref: number; score: number }[] } {
  return !!d && Array.isArray((d as { before?: unknown }).before) && Array.isArray((d as { after?: unknown }).after)
}
const verdictText: Record<string, string> = { ALL_RELEVANT: '全相关', PARTIAL: '部分相关', ALL_IRRELEVANT: '全无关' }
function fmt(n: number | null | undefined): string {
  return n == null ? '-' : n.toFixed(2)
}
function droppedRefs(detail: { before: number[]; after: { ref: number }[] }): number[] {
  const keep = new Set(detail.after.map((a) => a.ref))
  return detail.before.filter((r) => !keep.has(r))
}
/* ============ 旧 detail（Retrieve/Grade/Rerank 表格）渲染保留 ============ */

/** 旧 detail 只在无 StepMeta 的步上渲染（历史数据）；三个收窄取值器供模板直接用。 */
function legacyDetailRaw(step: AgentStep | undefined): unknown {
  return step?.detail && !metaOf(step) ? step.detail : null
}
function legacyRetrieve(step: AgentStep | undefined) {
  const detail = legacyDetailRaw(step)
  return isRetrieve(detail) ? detail : null
}
function legacyGrade(step: AgentStep | undefined) {
  const detail = legacyDetailRaw(step)
  return isGrade(detail) ? detail : null
}
function legacyRerank(step: AgentStep | undefined) {
  const detail = legacyDetailRaw(step)
  return isRerank(detail) ? detail : null
}
/** 该步是否带可展开的旧 detail。 */
function hasLegacyDetail(step: AgentStep | undefined): boolean {
  return Boolean(legacyRetrieve(step) || legacyGrade(step) || legacyRerank(step))
}
</script>

<template>
  <div v-if="trace" class="agent-trace">
    <!-- 顶部读数 -->
    <div class="trace-meta">
      <a-tag color="purple">{{ trace.paradigm }}</a-tag>
      <span v-if="trace.workflowId" class="meta-item mono">{{ trace.workflowId }}</span>
      <span class="meta-item">
        <span class="mono">{{ filtering ? `${displaySteps} / ${totalSteps}` : displaySteps }}</span> 步
      </span>
      <span v-if="trace.llmCallCount" class="meta-item">LLM ×<span class="mono">{{ trace.llmCallCount }}</span></span>
      <span v-if="trace.totalLatencyMs" class="meta-item num">{{ fmtLatency(trace.totalLatencyMs) }}</span>
      <span v-if="trace.promptHash" class="meta-item mono fingerprint" title="prompt 指纹（改任一层 prompt 自动变化，缓存随之失效）">§{{ trace.promptHash.slice(0, 12) }}</span>
    </div>

    <!-- 工具栏：搜索 / 类型过滤 / 一键展开 / 复制 -->
    <div class="trace-tools">
      <input v-model="query" type="search" class="trace-search" placeholder="搜索节点、工具、内容" aria-label="搜索轨迹">
      <div class="trace-filters" role="group" aria-label="按类型过滤">
        <button
          v-for="option in FILTERS"
          :key="option.value"
          type="button"
          class="filter-chip"
          :class="{ on: kindFilter === option.value }"
          @click="kindFilter = option.value"
        >{{ option.label }}</button>
      </div>
      <div class="tools-right">
        <button type="button" class="tool-btn" @click="toggleExpandAll">{{ allExpanded ? '收起全部' : '展开全部' }}</button>
        <button type="button" class="tool-btn" @click="copyTrace">{{ COPY_LABELS[copyState] }}</button>
      </div>
    </div>

    <p v-if="filtering && !displaySteps" class="trace-empty">没有匹配的步骤，换个关键字或切回「全部」。</p>

    <!-- ===== 诊断轨迹：异常置顶 + 阶段占比条 + 阶段分组 ===== -->
    <template v-if="grouped">
      <div v-if="analysis?.notices.length" class="trace-notices">
        <div v-for="(notice, i) in analysis.notices" :key="i" class="trace-notice" :class="{ loop: notice.kind === 'loop' }">
          {{ notice.text }}
        </div>
      </div>

      <div v-if="phaseShares.length" class="phase-share-bar" aria-hidden="true">
        <span
          v-for="share in phaseShares"
          :key="share.key"
          class="share-seg"
          :class="`seg-${share.key}`"
          :style="{ flexGrow: share.durationMs }"
          :title="`${share.label} · ${fmtLatency(share.durationMs)}`"
        />
      </div>

      <section v-for="group in visibleGroups" :key="group.key" class="phase">
        <button type="button" class="phase-head" :aria-expanded="!isPhaseCollapsed(group.key)" @click="togglePhase(group.key)">
          <span class="phase-caret" :class="{ closed: isPhaseCollapsed(group.key) }">▾</span>
          <span class="phase-title">{{ group.label }}</span>
          <span v-if="group.iterations" class="phase-meta">环内 <span class="mono">{{ group.iterations }}</span> 轮</span>
          <span v-if="group.notices.length" class="badge bad">本段异常 {{ group.notices.length }}</span>
          <span class="phase-meta phase-duration"><span class="mono">{{ fmtLatency(group.durationMs) }}</span></span>
        </button>

        <ol v-show="!isPhaseCollapsed(group.key)" class="timeline">
          <li v-for="(entry, i) in group.entries" :key="entry.key" class="timeline-item" :class="{ last: i === group.entries.length - 1 }">
            <span class="dot" :class="`dot-${entry.kind}`" :title="`步号 ${entry.index}`" />
            <!-- 工具条目（think+act 合并） -->
            <template v-if="entry.kind === 'tool' && entry.call">
              <div class="head">
                <span class="label">{{ entry.call.tool }}</span>
                <span class="kind-chip chip-tool">工具</span>
                <span v-if="entry.hitsBadge" class="badge" :class="{ bad: entry.hitsBadge === '无命中', verdict: entry.hitsBadge.startsWith('命中') }">{{ entry.hitsBadge }}</span>
                <span v-if="entry.failed" class="badge bad">工具报错</span>
                <span class="meta mono" :class="{ slow: entry.slow }">think {{ fmtLatency(entry.call.thinkDurationMs) }} · exec {{ fmtLatency(entry.call.durationMs) }}</span>
                <span v-if="entry.model" class="model" :title="entry.model">{{ entry.model }}</span>
                <button type="button" class="copy-btn" title="复制这一步" @click="writeClipboard(entryLines(entry).join('\n'))">复制</button>
              </div>
              <div class="io">
                <div class="io-line"><span class="io-label">in</span><span class="io-text mono">{{ entry.input }}</span></div>
                <div v-if="entry.call.result" class="io-line">
                  <span class="io-label">out</span>
                  <span class="io-text">{{ bodyOf(entry.call.result, entry.key) }}
                    <button v-if="hasMore(entry.call.result)" type="button" class="link-btn" @click="toggleExpanded(entry.key)">{{ isExpanded(entry.key) ? '收起' : '展开' }}</button>
                  </span>
                </div>
                <div v-if="entry.error" class="io-text error">{{ entry.error }}</div>
              </div>
              <!-- 检索命中明细表（数据来自工具 data 槽位；悬停看全文） -->
              <TraceHitsTable v-if="entry.chunks?.length" :chunks="entry.chunks" :cited-refs="citedRefs" />
            </template>

            <!-- 其余步骤（决策已并入 output；门禁 / 问齐 / replan / 终态） -->
            <template v-else>
              <div class="head">
                <span class="label">{{ entry.label }}</span>
                <span v-if="entry.kind !== 'plain' && entry.kind !== 'decision'" class="kind-chip" :class="`chip-${entry.kind}`">{{ kindLabel(entry.kind as StepKind) }}</span>
                <span v-if="entry.verdict" class="badge verdict">裁决 {{ entry.verdict }}</span>
                <span v-if="entry.loopBreak" class="badge bad">迭代上限</span>
                <span class="meta">
                  <span class="mono" :class="{ slow: entry.slow }">{{ fmtLatency(entry.durationMs) }}</span>
                  <span v-if="entry.tokens" class="mono tok" :title="tokenTitle(entry)">{{ entry.tokens }} tok</span>
                </span>
                <span v-if="entry.model" class="model" :title="entry.model">{{ entry.model }}</span>
                <button type="button" class="copy-btn" title="复制这一步" @click="writeClipboard(entryLines(entry).join('\n'))">复制</button>
              </div>
              <div v-if="entry.note" class="step-note">{{ entry.note }}</div>
              <div v-if="entry.input || entry.output || entry.writes.length || entry.error" class="io">
                <div v-if="entry.input" class="io-line"><span class="io-label">in</span><span class="io-text">{{ entry.input }}</span></div>
                <div v-if="entry.output" class="io-line">
                  <span class="io-label">out</span>
                  <span class="io-text">{{ bodyOf(entry.output, entry.key) }}
                    <button v-if="hasMore(entry.output) || hasLegacyDetail(entry.step)" type="button" class="link-btn" @click="toggleExpanded(entry.key)">{{ isExpanded(entry.key) ? '收起' : '展开' }}</button>
                  </span>
                </div>
                <div v-if="entry.writes.length" class="io-line">
                  <span class="io-label">写入槽位</span>
                  <span class="io-text">
                    <span v-for="w in entry.writes" :key="w.key" class="slot-chip" :title="w.title">{{ w.label }}</span>
                  </span>
                </div>
                <div v-if="entry.error" class="io-text error">{{ entry.error }}</div>
              </div>
              <!-- 旧 detail 表格（历史数据：retrieve 命中 / grade 评分 / rerank 重排） -->
              <div v-if="hasLegacyDetail(entry.step) && isExpanded(entry.key)" class="legacy-detail">
                <table v-if="legacyRetrieve(entry.step)" class="detail-table">
                  <thead><tr><th>ref</th><th>分数</th><th>原始</th><th>来源文档</th><th>通道</th><th class="wide-col">预览</th></tr></thead>
                  <tbody>
                    <tr v-for="c in legacyRetrieve(entry.step)!.chunks" :key="c.ref">
                      <td>{{ c.ref }}</td><td>{{ fmt(c.score) }}</td><td>{{ fmt(c.originalScore) }}</td>
                      <td>{{ c.docName }}</td><td>{{ c.channel }}</td><td class="text-cell">{{ c.preview }}</td>
                    </tr>
                  </tbody>
                </table>
                <template v-else-if="legacyGrade(entry.step)">
                  <div class="grade-meta">
                    <a-tag :color="legacyGrade(entry.step)!.verdict === 'ALL_IRRELEVANT' ? 'red' : 'green'">
                      {{ verdictText[legacyGrade(entry.step)!.verdict] || legacyGrade(entry.step)!.verdict }}
                    </a-tag>
                    <span>相关 {{ legacyGrade(entry.step)!.relevant }}/{{ legacyGrade(entry.step)!.total }}</span>
                    <span>均分 {{ legacyGrade(entry.step)!.avgScore.toFixed(2) }}</span>
                  </div>
                  <table class="detail-table">
                    <thead><tr><th>ref</th><th>分数</th><th>相关?</th><th class="wide-col">理由</th></tr></thead>
                    <tbody>
                      <tr v-for="g in legacyGrade(entry.step)!.grades" :key="g.ref" :class="{ dim: !g.relevant }">
                        <td>{{ g.ref }}</td><td>{{ g.score.toFixed(2) }}</td><td>{{ g.relevant ? '✓' : '✗' }}</td>
                        <td class="text-cell">{{ g.reason || '—' }}</td>
                      </tr>
                    </tbody>
                  </table>
                </template>
                <div v-else-if="legacyRerank(entry.step)" class="rerank-detail">
                  <div class="rerank-line">
                    <span class="rerank-label">重排前 {{ legacyRerank(entry.step)!.before.length }} 条</span>
                    <span v-for="r in legacyRerank(entry.step)!.before" :key="'b' + r" class="ref-chip">{{ r }}</span>
                  </div>
                  <div class="rerank-line">
                    <span class="rerank-label">保留 {{ legacyRerank(entry.step)!.after.length }} 条</span>
                    <span v-for="(r, i) in legacyRerank(entry.step)!.after" :key="'a' + i" class="ref-chip keep">{{ r.ref < 0 ? '—' : r.ref }}<small>{{ fmt(r.score) }}</small></span>
                  </div>
                  <div v-if="droppedRefs(legacyRerank(entry.step)!).length" class="rerank-line">
                    <span class="rerank-label">淘汰</span>
                    <span v-for="r in droppedRefs(legacyRerank(entry.step)!)" :key="'d' + r" class="ref-chip drop">{{ r }}</span>
                  </div>
                </div>
              </div>
              <!-- 检索命中明细表（数据来自工具 data 槽位；悬停看全文） -->
              <TraceHitsTable v-if="entry.chunks?.length" :chunks="entry.chunks" :cited-refs="citedRefs" />
            </template>
          </li>
        </ol>
      </section>
    </template>

    <!-- ===== 非诊断轨迹：单段时间线 ===== -->
    <ol v-else class="timeline">
      <li v-for="(entry, i) in visibleFlat" :key="entry.key" class="timeline-item" :class="{ last: i === visibleFlat.length - 1 }">
        <span class="dot" :class="`dot-${entry.kind}`" :title="`步号 ${entry.index}`" />
        <template v-if="entry.kind === 'tool' && entry.call">
          <div class="head">
            <span class="label">{{ entry.call.tool }}</span>
            <span class="kind-chip chip-tool">工具</span>
            <span v-if="entry.hitsBadge" class="badge" :class="{ bad: entry.hitsBadge === '无命中' }">{{ entry.hitsBadge }}</span>
            <span class="meta mono" :class="{ slow: entry.slow }">think {{ fmtLatency(entry.call.thinkDurationMs) }} · exec {{ fmtLatency(entry.call.durationMs) }}</span>
            <button type="button" class="copy-btn" @click="writeClipboard(entryLines(entry).join('\n'))">复制</button>
          </div>
          <div class="io">
            <div class="io-line"><span class="io-label">in</span><span class="io-text mono">{{ entry.input }}</span></div>
            <!-- out 用归纳文案（检索类 = 「命中 N 条」，明细走下方命中表）——与知识轴口径一致。
                 先前这里渲染的是 entry.call.result 原始观测原文，与命中表内容重复，
                 读者得在两处之间来回对照；entriesOf 早已算好 output 却没被用上。 -->
            <div v-if="entry.output" class="io-line">
              <span class="io-label">out</span>
              <span class="io-text">{{ bodyOf(entry.output, entry.key) }}
                <button v-if="hasMore(entry.output)" type="button" class="link-btn" @click="toggleExpanded(entry.key)">{{ isExpanded(entry.key) ? '收起' : '展开' }}</button>
              </span>
            </div>
          </div>
              <!-- 检索命中明细表（数据来自工具 data 槽位；悬停看全文） -->
              <TraceHitsTable v-if="entry.chunks?.length" :chunks="entry.chunks" :cited-refs="citedRefs" />
        </template>
        <template v-else>
          <div class="head">
            <span class="label">{{ entry.label }}</span>
            <span v-if="entry.kind !== 'plain'" class="kind-chip" :class="`chip-${entry.kind}`">{{ kindLabel(entry.kind as StepKind) }}</span>
            <span class="meta">
              <span class="mono" :class="{ slow: entry.slow }">{{ fmtLatency(entry.durationMs) }}</span>
              <span v-if="entry.tokens" class="mono tok" :title="tokenTitle(entry)">{{ entry.tokens }} tok</span>
            </span>
            <button type="button" class="copy-btn" @click="writeClipboard(entryLines(entry).join('\n'))">复制</button>
          </div>
          <div v-if="entry.note" class="step-note">{{ entry.note }}</div>
          <div v-if="entry.input || entry.output || entry.writes.length" class="io">
            <div v-if="entry.input" class="io-line"><span class="io-label">in</span><span class="io-text">{{ entry.input }}</span></div>
            <div v-if="entry.output" class="io-line">
              <span class="io-label">out</span>
              <span class="io-text">{{ bodyOf(entry.output, entry.key) }}
                <button v-if="hasMore(entry.output) || hasLegacyDetail(entry.step)" type="button" class="link-btn" @click="toggleExpanded(entry.key)">{{ isExpanded(entry.key) ? '收起' : '展开' }}</button>
              </span>
            </div>
            <div v-if="entry.writes.length" class="io-line">
              <span class="io-label">写入槽位</span>
              <span class="io-text">
                <span v-for="w in entry.writes" :key="w.key" class="slot-chip" :title="w.title">{{ w.label }}</span>
              </span>
            </div>
          </div>
              <!-- 检索命中明细表（数据来自工具 data 槽位；悬停看全文） -->
              <TraceHitsTable v-if="entry.chunks?.length" :chunks="entry.chunks" :cited-refs="citedRefs" />
        </template>
      </li>
    </ol>
  </div>
  <a-empty v-else description="等待执行轨迹…" :image="undefined" style="padding:16px 0" />
</template>

<style scoped>
.agent-trace { padding: 4px 2px; font-size: 12px; }
.trace-meta { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; color: var(--color-ink-secondary); }
/* 本仓的等宽令牌叫 --font-display（JetBrains Mono）；此前写的 --font-mono 并不存在，
   一直靠 fallback 落到浏览器默认的 monospace 上 */
.meta-item.mono, .mono { font-family: var(--font-display, monospace); }
.meta-item { font-size: 12px; }
.meta-item.mono { font-size: 11px; color: var(--color-ink-tertiary); }
.fingerprint { cursor: default; }

/* 工具栏 */
.trace-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.trace-search {
  flex: 1 1 180px; min-width: 140px; padding: 8px 12px; font-size: 13px;
  border: 1px solid var(--color-border); border-radius: var(--radius-sm);
  background: var(--color-surface); color: var(--color-ink);
}
.trace-search:focus { outline: none; border-color: var(--color-primary); }
.trace-filters { display: flex; gap: 4px; }
.filter-chip {
  padding: 2px 8px; font-size: 11px; border: 1px solid var(--color-border-light); border-radius: 10px;
  background: transparent; color: var(--color-ink-secondary); cursor: pointer;
}
.filter-chip.on { background: var(--color-primary); border-color: var(--color-primary); color: #fff; }
.tools-right { display: flex; gap: 4px; margin-left: auto; }
.tool-btn {
  padding: 2px 8px; font-size: 11px; border: none; border-radius: var(--radius-sm);
  background: var(--color-surface-secondary); color: var(--color-ink-secondary); cursor: pointer;
}
.tool-btn:hover { color: var(--color-primary); }
.trace-empty { padding: 12px 0; text-align: center; color: var(--color-ink-tertiary); }

/* 异常置顶与占比条 */
.trace-notices { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; }
.trace-notice {
  padding: 4px 8px; border-radius: var(--radius-sm); font-size: 11px;
  background: rgba(207, 19, 34, 0.06); color: #cf1322;
}
.trace-notice.loop { background: rgba(212, 136, 15, 0.08); color: #d4880f; }
.phase-share-bar { display: flex; gap: 2px; height: 5px; margin-bottom: 10px; border-radius: 3px; overflow: hidden; }
.share-seg { flex-grow: 1; background: var(--color-primary, #6c5ce7); opacity: 0.75; }
.share-seg.seg-intake { background: #389e0d; }
.share-seg.seg-inv { background: #6c5ce7; }
.share-seg.seg-res { background: #2f7ff5; }
.share-seg.seg-ver { background: #13c2c2; }
.share-seg.seg-terminal { background: #8c8c8c; }

/* 阶段分组 */
.phase { margin-bottom: 4px; }
.phase-head {
  display: flex; align-items: center; gap: 8px; width: 100%; padding: 5px 6px;
  border: none; border-radius: var(--radius-sm); background: var(--color-surface-secondary);
  font-size: 12px; color: var(--color-ink); cursor: pointer; text-align: left;
}
.phase-head:hover { background: var(--color-surface); }
.phase-caret { font-size: 10px; color: var(--color-ink-tertiary); transition: transform 0.15s; }
.phase-caret.closed { transform: rotate(-90deg); }
.phase-title { font-weight: 600; }
.phase-meta { font-size: 11px; color: var(--color-ink-secondary); }
.phase-duration { margin-left: auto; }
.badge {
  padding: 0 6px; border-radius: 9px; font-size: 11px; line-height: 17px;
  background: var(--color-surface); border: 1px solid var(--color-border-light);
}
.badge.bad { color: #cf1322; border-color: rgba(207, 19, 34, 0.35); background: rgba(207, 19, 34, 0.05); }
.badge.verdict { color: #d4880f; border-color: rgba(212, 136, 15, 0.35); background: rgba(212, 136, 15, 0.06); }

/* 时间线（留白节奏对齐 agent-framework：行距与步距放宽，信息不挤） */
.timeline { list-style: none; margin: 4px 0 0; padding: 0 0 0 6px; }
.timeline-item { position: relative; padding: 6px 0 12px 20px; }
.timeline-item::before {
  content: ''; position: absolute; left: 4px; top: 18px; bottom: -6px; width: 1px;
  background: var(--color-border-light);
}
.timeline-item.last::before { display: none; }
.dot {
  position: absolute; left: 0; top: 7px; width: 9px; height: 9px; border-radius: 50%;
  border: 2px solid var(--color-surface); box-shadow: 0 0 0 1px var(--color-border-light);
}
.dot-llm { background: #6c5ce7; }
.dot-tool { background: #2f7ff5; }
.dot-condition { background: #d4880f; }
.dot-human { background: #389e0d; }
.dot-parallel { background: #13c2c2; }
.dot-sub { background: #722ed1; }
.dot-fail { background: #cf1322; }
.dot-plain { background: #8c8c8c; }

.head { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; margin-bottom: 1px; }
.label { font-weight: 600; font-size: 12.5px; }
.kind-chip {
  padding: 0 6px; border-radius: 9px; font-size: 10px; line-height: 16px; color: #fff;
}
.chip-llm { background: #6c5ce7; }
.chip-tool { background: #2f7ff5; }
.chip-condition { background: #d4880f; }
.chip-human { background: #389e0d; }
.chip-parallel { background: #13c2c2; }
.chip-sub { background: #722ed1; }
.chip-fail { background: #cf1322; }
/* 行头右侧读数（耗时 / token）：耗时是这条轨迹里最常被扫的数字，用主前景色而不是三级灰——
   三级灰在浅底上弱到要凑近看，等于把「哪一步慢」这个最该一眼看到的信息藏了起来。
   等宽 + tabular-nums 让各行的数字纵向对得齐，扫读时位数不跳（与 agent-framework 同口径）。 */
.meta {
  margin-left: auto; display: inline-flex; gap: 6px; align-items: baseline;
  font-size: 12px; color: var(--color-ink-secondary);
}
.meta .mono {
  font-family: var(--font-display, monospace);
  font-variant-numeric: tabular-nums;
  font-weight: 500;
  color: var(--color-ink);
}
/* token 数是补充读数，不该和耗时抢注意力 */
.meta .tok { font-size: 11px; font-weight: 400; color: var(--color-ink-tertiary); }
.mono.slow, .meta .slow { color: #d4880f; font-weight: 600; }
.tok { font-size: 10px; }
.model { font-size: 10px; color: var(--color-ink-tertiary); max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.copy-btn {
  padding: 0 6px; font-size: 10px; border: none; border-radius: var(--radius-sm);
  background: transparent; color: var(--color-ink-tertiary); cursor: pointer; visibility: hidden;
}
.timeline-item:hover .copy-btn { visibility: visible; }
.copy-btn:hover { color: var(--color-primary); }

.step-note { margin: 2px 0; font-size: 11px; color: var(--color-ink-tertiary); }

/* 步内容 in/out/槽位：分块卡片式（左缩进 + 浅底），与行头视觉分层 */
.io {
  margin-top: 6px; padding: 6px 10px 7px;
  display: flex; flex-direction: column; gap: 4px;
  border-left: 2px solid var(--color-border-light);
  background: var(--color-surface-secondary);
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
}
.io-line { display: flex; gap: 8px; align-items: baseline; }
.io-label {
  flex: none; font-size: 10px; line-height: 18px; padding: 0 6px; border-radius: 4px;
  color: var(--color-ink-tertiary); background: var(--color-surface);
  min-width: 2.6em; text-align: center;
}
.io-text { font-size: 11.5px; color: var(--color-ink-secondary); word-break: break-all; line-height: 1.6; }
.io-text.mono { font-family: var(--font-display, monospace); font-size: 12px; }
.io-text.error { color: #cf1322; }
.link-btn {
  border: none; background: none; padding: 0 4px; font-size: 11px;
  color: var(--color-primary); cursor: pointer;
}
.slot-chip {
  display: inline-block; margin: 1px 3px 1px 0; padding: 0 7px; border-radius: 9px;
  font-size: 11px; line-height: 17px; background: var(--color-surface-secondary);
  color: var(--color-ink-secondary); cursor: default;
}

/* 命中表的样式随组件搬进了 TraceHitsTable（scoped，不再从父组件继承） */

/* 旧 detail 表格（历史数据） */
.legacy-detail { margin-top: 6px; padding: 6px 8px; border-radius: var(--radius-sm); background: var(--color-surface-secondary); }
.detail-table { width: 100%; border-collapse: collapse; font-size: 11px; }
.detail-table th { text-align: left; font-weight: 500; padding: 3px 6px; color: var(--color-ink-tertiary); white-space: nowrap; }
.detail-table td { padding: 3px 6px; vertical-align: top; border-top: 1px solid var(--color-border-light); }
.detail-table tr.dim td { opacity: 0.45; }
.text-cell, .wide-col { max-width: 240px; color: var(--color-ink-secondary); word-break: break-all; }
.grade-meta { display: flex; gap: 10px; align-items: center; font-size: 11px; color: var(--color-ink-secondary); margin-bottom: 4px; }
.rerank-line { margin: 3px 0; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.rerank-label { color: var(--color-ink-tertiary); margin-right: 4px; }
.ref-chip {
  display: inline-flex; align-items: baseline; gap: 2px; padding: 1px 7px;
  border-radius: 10px; font-size: 11px; background: var(--color-surface-secondary);
}
.ref-chip small { font-size: 10px; color: var(--color-ink-tertiary); }
.ref-chip.keep { color: var(--color-success, #389e0d); }
.ref-chip.drop { opacity: 0.4; text-decoration: line-through; }
</style>
