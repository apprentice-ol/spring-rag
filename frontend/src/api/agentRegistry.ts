import { http } from './client'

/** Agent 骨架注册表（GET /agent/registry）：范式选择器与管理后台「Agent 清单」共用。
 *  旧字段（type/label/description/stages/tools）结构稳定；详情字段为管理页增量，旧后端缺省。 */

export interface SlotView {
  name: string
  required: boolean
  question: string
  hint: string | null
}

export interface StageView {
  name: string
  /** LOOP / DETERMINISTIC / AGENT_CALL / 自定义 */
  nodeKind: string
  systemPromptKey: string | null
  tools: string[]
  maxSteps: number
  /** 产出护栏 JSON Schema 是否存在 */
  guard: boolean
  /** 是否条件跳过（when 声明） */
  conditional: boolean
  errorPolicy: string | null
  targetAgentId: string | null
}

export interface PolicyView {
  replan: boolean
  maxAdjustRetries: number
  /** 0 = 不限 */
  maxLlmCalls: number
  /** 0 = 不限 */
  timeoutSeconds: number
}

export interface AgentView {
  type: string
  label: string
  description: string
  stages: string[]
  tools: string[]
  intentDomain?: string
  capabilities?: string[]
  /** 组合的 Workflow 标识（trace 指纹 / 绑定用） */
  workflowId?: string
  /** Agent 人格层 prompt key（角色/语气/交付风格） */
  agentPromptKeys?: string[]
  /** Workflow 流程层 prompt key（阶段 system / 抽槽 / replan / 答案） */
  workflowPromptKeys?: string[]
  /** 三个特殊流程 key（null = 未声明） */
  answerPromptKey?: string | null
  slotExtractPromptKey?: string | null
  replanPromptKey?: string | null
  /** 链路级 key 的展示归类（编排链消费、不挂 agent 骨架） */
  linkPromptKeys?: string[]
  slots?: SlotView[]
  stageDetails?: StageView[]
  policy?: PolicyView
}

export interface RegistryView {
  defaultAgent: string
  agents: AgentView[]
}

export function getRegistry() {
  return http.get<RegistryView>('/agent/registry')
}
