/**
 * 执行轨迹的信息重组：阶段分组 / 工具调用聚合 / 异常统计 / 决策翻译。
 *
 * 节点流水账（槽位抽取 → 信息门禁 → …）对两类读者都是噪音：使用者问「我该补什么」，
 * 开发者问「卡在哪、为什么、热点在哪」。这里把 trace.steps 重组成可读结构（对齐
 * agent-framework 仓 TraceDrawer 的动作范式）：
 * - phases：入口问齐 → 定位 → 纠正 → 校验 → 终态，各段轮次/耗时/异常
 * - toolCalls：think 步的工具协议 JSON 与 act 步的执行结果配对成一次「调用」
 * - notices：迭代上限、空命中等需要眼睛立刻看到的事
 *
 * 兼容两代数据：新内核轨迹步带 detail 元数据（nodeType/slotWrites/toolCall/tokens…），
 * 旧数据（含旧 ReAct 名）按 action 后缀与映射表退化判定。
 */
import type { AgentStep, StepMeta } from '../api/chat'

/** 步类型（时间线圆点与徽标共用的色调词汇）。 */
export type StepKind = 'llm' | 'tool' | 'condition' | 'human' | 'parallel' | 'sub' | 'fail' | 'plain'

const KIND_BY_NODE_TYPE: Record<string, StepKind> = {
  LLM: 'llm',
  TOOL: 'tool',
  CONDITION: 'condition',
  HUMAN: 'human',
  PARALLEL: 'parallel',
  SUB_WORKFLOW: 'sub',
}

/** 类型徽标文案；plain 不出徽标，只有圆点。 */
const KIND_LABELS: Record<StepKind, string> = {
  llm: 'LLM',
  tool: '工具',
  condition: '条件',
  human: '人工',
  parallel: '并行',
  sub: '子流程',
  fail: '失败',
  plain: '',
}

export function kindLabel(kind: StepKind): string {
  return KIND_LABELS[kind] ?? ''
}

/** 旧链路（无 detail 元数据）的节点类型推断：仅用于着色与徽标。 */
const LEGACY_KIND: Record<string, StepKind> = {
  classify: 'llm',
  route: 'condition',
  chitchat: 'llm',
  ask_user: 'human',
  rewrite: 'llm',
  retrieve: 'tool',
  answer: 'llm',
  conclude: 'llm',
  escalate_node: 'human',
  slots_gate: 'condition',
  slots_regate: 'condition',
  auto_gate: 'condition',
}

/**
 * 查询理解链节点的类型标注。
 *
 * 这些都是 CUSTOM 节点，而 LLM 调用发生在执行器内部的聊天客户端里，内核拿不到 token 用量，
 * 于是 `kindOf` 的「有 token 才算 LLM」兜底判定对它们不成立——不额外标注的话，
 * 「意图识别」「问题重写」会渲染成没有颜色的普通圆点，恰好把最该一眼区分的两类步骤抹平。
 */
const NODE_KINDS: Record<string, StepKind> = {
  kb_normalize: 'plain',
  kb_classify: 'llm',
  kb_route: 'condition',
  kb_route_gate: 'condition',
  kb_shortcircuit: 'condition',
  kb_rewrite: 'llm',
  kb_retrieve: 'tool',
  kb_critique: 'condition',
  kb_done: 'plain',
  answer: 'llm',
}

/** 轨迹步的结构化元数据（新内核经 detail 通道下发）。 */
export function metaOf(step: AgentStep): StepMeta | null {
  const detail = step.detail as StepMeta | undefined
  return detail && typeof detail === 'object' && ('nodeType' in detail || 'slotWrites' in detail || 'toolCall' in detail)
    ? detail
    : null
}

/** 判断轨迹步的类型：detail.nodeType 优先；CUSTOM 有 token 按 LLM；无 detail 按后缀与映射表。 */
export function kindOf(step: AgentStep): StepKind {
  const meta = metaOf(step)
  if (meta?.status === 'FAILED' || meta?.error) {
    return 'fail'
  }
  if (meta?.nodeType) {
    const mapped = KIND_BY_NODE_TYPE[meta.nodeType]
    if (mapped) {
      return mapped
    }
    if ((meta.promptTokens ?? 0) + (meta.completionTokens ?? 0) > 0) {
      return 'llm'
    }
  }
  const declared = NODE_KINDS[step.action]
  if (declared) {
    return declared
  }
  if (/_think$/.test(step.action) || /^replan/.test(step.action)) {
    return 'llm'
  }
  if (/_act$/.test(step.action) || step.action === 'kb_retrieve') {
    return 'tool'
  }
  if (/_decide$/.test(step.action) || /_gate$/.test(step.action) || /_regate$/.test(step.action)) {
    return 'condition'
  }
  return LEGACY_KIND[step.action] ?? 'plain'
}

/** 工作流节点 id → 面向用户的中文标签。 */
const NODE_LABELS: Record<string, string> = {
  extract_slots: '槽位抽取',
  slots_gate: '信息门禁',
  collect_slots: '一次问齐',
  slots_regate: '补答再判定',
  auto_resolve: '自主补全',
  auto_gate: '补全门禁',
  inv_think: '定位 · 推理',
  inv_act: '定位 · 执行工具',
  inv_decide: '定位 · 是否继续',
  replan_1: '阶段评估 Ⅰ',
  res_think: '报文 · 推理',
  res_act: '报文 · 执行工具',
  res_decide: '报文 · 是否继续',
  replan_2: '阶段评估 Ⅱ',
  ver_think: '校验 · 推理',
  ver_act: '校验 · 执行工具',
  ver_decide: '校验 · 是否继续',
  escalate_node: '升级转人工',
  conclude: '结论直答',
  kb_normalize: '归一化',
  kb_classify: '意图识别',
  kb_route: '路由',
  kb_route_gate: '路由闸门',
  kb_shortcircuit: '短路直答',
  kb_rewrite: '问题重写',
  kb_retrieve: '知识检索',
  kb_critique: '充分性判定',
  kb_done: '检索完成',
  answer: '生成答案',
  react_think: '推理',
  react_act: '执行工具',
  react_decide: '是否继续',
  // 旧 ReAct 名（历史数据）
  retrieve: '检索',
  rewrite: '问题重写',
  grade: '评分',
  rerank: '重排',
  finish: '汇总',
  think: '推理',
}

export function nodeLabel(nodeId: string): string {
  return NODE_LABELS[nodeId] ?? nodeId
}

/** 槽位键 → 中文标签（「写入槽位」行用；未收录的键回退原键名）。 */
const SLOT_LABELS: Record<string, string> = {
  missing_count: '缺项数',
  slot_extract_raw: '抽槽原文',
  llm_calls: 'LLM 调用数',
  escalate_reason: '升级原因',
  replan_verdict: '裁决',
  replan_note: '修正要求',
  pending_ask: '待答问题',
  pending_ask_slots: '期望补充',
  user_clarify: '用户补充',
  clarify_question: '追问问题',
  inferred_slots: '补全溯源',
  final_output: '最终输出',
  trace_id: 'traceId',
  kb_chunks: '检索预览',
  kb_chunks_data: '命中数据',
  tool_chunks: '命中累积',
  scratchpad: '过程记录',
  normalized_query: '归一化查询',
  intent: '意图域',
  intent_confidence: '置信度',
  needs_retrieval: '需要检索',
  route_target: '路由目标',
  rewritten_query: '改写查询',
  rewrite_round: '重写轮次',
  sufficiency: '充分性',
  critique_reason: '判定理由',
  history: '对话历史',
}

export function slotLabel(key: string): string {
  return SLOT_LABELS[key] ?? key
}

/** 阶段划分：节点 id 归组（与后端图的命名规约对齐）。 */
const PHASE_DEFS = [
  { key: 'intake', label: '入口问齐', match: /^(extract_slots|slots_gate|auto_resolve|auto_gate|collect_slots|slots_regate)$/ },
  { key: 'inv', label: '① 定位', match: /^(inv_|replan_1)/ },
  { key: 'res', label: '② 纠正', match: /^(res_|replan_2)/ },
  { key: 'ver', label: '③ 校验', match: /^(ver_)/ },
  { key: 'terminal', label: '终态', match: /^(escalate_node|conclude)$/ },
  { key: 'react', label: '工具循环', match: /^(react_|kb_)/ },
]

/** 是否按阶段分组渲染（诊断链；react/knowledge 单段不分组）。 */
export function isDiagnosisTrace(steps: AgentStep[]): boolean {
  return steps.some((s) => /^(inv_|res_|ver_|extract_slots|collect_slots)/.test(s.action))
}

/** think 步输出里的工具协议 JSON（{"tool":...,"args":{...}}）。 */
export function parseToolCall(output?: string): { tool: string; args: Record<string, unknown> } | null {
  if (typeof output !== 'string' || !output.trimStart().startsWith('{')) {
    return null
  }
  try {
    const parsed = JSON.parse(output.trim())
    if (parsed && typeof parsed.tool === 'string') {
      return parsed
    }
  } catch {
    /* 非协议输出（answer/ask_user 等）不参与工具配对 */
  }
  return null
}

/** 非工具决策输出（answer / ask_user / escalate）→ 使用者能读懂的句子；null = 不是决策 JSON。 */
export function translateDecision(output?: string): string | null {
  if (typeof output !== 'string' || !output.trimStart().startsWith('{')) {
    return null
  }
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(output.trim())
  } catch {
    return null
  }
  if (typeof parsed.answer === 'string' && parsed.answer.trim()) {
    return `给出结论：${parsed.answer}`
  }
  if (typeof parsed.ask_user === 'string' && parsed.ask_user.trim()) {
    const slots = Array.isArray(parsed.slots) && parsed.slots.length
      ? `（期望补充：${parsed.slots.join('、')}）`
      : ''
    return `向用户提问：${parsed.ask_user}${slots}`
  }
  if (typeof parsed.escalate === 'string' && parsed.escalate.trim()) {
    return `升级人工：${parsed.escalate}`
  }
  return null
}

/** 结果文本里的命中条数（「命中 N 条」/「无命中」）。 */
export function hitCountOf(result?: string): number | null {
  if (!result) {
    return null
  }
  if (/无命中|未检索到/.test(result)) {
    return 0
  }
  const matched = result.match(/命中\s*(\d+)\s*条/)
  return matched ? Number(matched[1]) : null
}

/** 一次日志查询的可读时间窗。 */
export function describeWindow(args?: Record<string, unknown>): string {
  const start = args?.start ? String(args.start).replace('T', ' ').slice(0, 16) : ''
  const end = args?.end ? String(args.end).replace('T', ' ').slice(0, 16) : ''
  if (start && end) {
    return `${start} ~ ${end}`
  }
  return start || '不限时间'
}

/** 工具参数 → `{k=v, …}` 一行。 */
export function argsLine(args?: Record<string, unknown>): string {
  const pairs = Object.entries(args ?? {})
    .map(([key, value]) => `${key}=${String(value).slice(0, 40)}`)
    .join(', ')
  return `{${pairs}}`
}

/** 行内短摘：去掉代码围栏与多余空白后截断。 */
export function clip(text: string | undefined | null, max = 40): string {
  const value = typeof text === 'string' ? text : ''
  const flat = value.replace(/```[a-z]*/gi, ' ').replace(/`/g, '').replace(/\s+/g, ' ').trim()
  return flat.length > max ? `${flat.slice(0, max)}…` : flat
}

/**
 * 检索命中数据（工具 data 通道写入的槽位）。
 *
 * knowledge 轴 = {@code kb_chunks_data}（{query, chunks}）；react 轴 = {@code react_tool_chunks} / {@code tool_chunks}（数组）。
 *
 * 字段口径对齐 agent-framework 的命中表：score 是融合/精排后的分（决定排序），
 * originalScore 是通道原始分（cosine / BM25，说明这一条本身有多像），
 * 两者并排才看得出「两路都命中」和「单路硬捞上来」的区别。
 */
export interface ChunkRow {
  ref?: number
  content?: string
  score?: number
  originalScore?: number
  channel?: string
  docName?: string | null
  heading?: string | null
}

export interface TracePhase {
  key: string
  label: string
  steps: AgentStep[]
  /** 工具环轮次 = think 节点执行次数。 */
  iterations: number
  durationMs: number
  notices: TraceNotice[]
}

export interface TraceNotice {
  kind: 'loop' | 'empty'
  stepIndex: number
  text: string
}

/** think 与其后的 act 配对成的一次「调用」。 */
export interface TraceToolCall {
  tool: string
  args: Record<string, unknown>
  model: string
  ok: boolean | null
  error: string
  result: string
  hits: number | null
  thinkIndex: number
  actIndex: number
  thinkDurationMs: number
  durationMs: number
}

export interface TraceAnalysis {
  phases: TracePhase[]
  toolCalls: TraceToolCall[]
  notices: TraceNotice[]
}

/** 重组轨迹：阶段分组 + 工具调用配对 + 异常统计。 */
export function analyzeTrace(steps: AgentStep[]): TraceAnalysis {
  const phases = PHASE_DEFS.map((def) => ({ ...def, steps: [] as AgentStep[], iterations: 0, durationMs: 0, notices: [] as TraceNotice[] }))
  const phaseOf = (nodeId: string) => phases.find((phase) => phase.match.test(nodeId))
  steps.forEach((step) => {
    const phase = phaseOf(step.action)
    if (phase) {
      phase.steps.push(step)
      phase.durationMs += step.latencyMs ?? 0
    }
  })
  phases.forEach((phase) => {
    phase.iterations = phase.steps.filter((step) => /_think$/.test(step.action)).length
  })

  // 工具调用：think 的协议 JSON 判定「这是一次工具调用」，act 的结构化记录（toolCall）是权威副本
  const toolCalls: TraceToolCall[] = []
  steps.forEach((step, index) => {
    const parsed = parseToolCall(step.outputSummary)
    if (!parsed) {
      return
    }
    const act = steps.slice(index + 1).find((next) => /_act$/.test(next.action))
    const actMeta = act ? metaOf(act) : null
    const call = actMeta?.toolCall
    const result = act?.outputSummary ?? ''
    toolCalls.push({
      tool: call?.name ?? parsed.tool,
      args: (call?.arguments as Record<string, unknown>) ?? parsed.args ?? {},
      model: metaOf(step)?.model ?? '',
      ok: call ? call.ok !== false : null,
      error: call?.error ?? '',
      result,
      hits: hitCountOf(result),
      thinkIndex: step.stepIndex,
      actIndex: act?.stepIndex ?? -1,
      thinkDurationMs: step.latencyMs ?? 0,
      durationMs: act?.latencyMs ?? 0,
    })
  })

  // 异常：迭代上限 / 日志查询空命中
  const notices: TraceNotice[] = []
  steps.forEach((step) => {
    const meta = metaOf(step)
    if (/max_iterations/.test(meta?.routeDetail ?? '')) {
      notices.push({ kind: 'loop', stepIndex: step.stepIndex, text: `${meta!.routeDetail}（达到循环治理上限）` })
    }
  })
  toolCalls.forEach((call) => {
    if (call.tool === 'query_logs' && call.hits === 0) {
      notices.push({
        kind: 'empty',
        stepIndex: call.thinkIndex,
        text: `日志查询无命中：关键字「${String(call.args.keyword ?? call.args.trace_id ?? '')}」（${describeWindow(call.args)}）`,
      })
    }
  })
  phases.forEach((phase) => {
    const indexes = new Set(phase.steps.map((step) => step.stepIndex))
    phase.notices = notices.filter((notice) => indexes.has(notice.stepIndex))
  })

  return {
    phases: phases.filter((phase) => phase.steps.length > 0),
    toolCalls,
    notices,
  }
}
