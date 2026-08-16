import { http } from './client'
import type { AgentStepDetail, AgentTrace } from './chat'

export interface AgentTraceStep {
  stepIndex: number
  action: string
  thought: string
  inputSummary: string
  outputSummary: string
  latencyMs: number
  detail?: AgentStepDetail
}

/** 后端 AgentTraceEntity 镜像；steps 是 jsonb 字符串，前端用 parseSteps 解析。 */
export interface AgentTraceRecord {
  id: number
  conversationId: string | null
  messageId: number | null
  traceId: string | null
  paradigm: string
  question: string | null
  steps: string | null
  llmCallCount: number | null
  totalLatencyMs: number | null
  createTime: string
}

export interface AgentTracePage {
  total: number
  records: AgentTraceRecord[]
}

export interface AgentTraceStat {
  paradigm: string
  cnt: number
  avg_steps: number
}

export function parseSteps(s: string | null): AgentTraceStep[] {
  if (!s) return []
  try {
    return JSON.parse(s)
  } catch {
    return []
  }
}

/** DB 轨迹记录 → AgentTraceTree 可渲染的 AgentTrace（steps jsonb 解析；面板/对话气泡共用）。 */
export function toAgentTrace(rec: AgentTraceRecord): AgentTrace {
  return {
    paradigm: rec.paradigm,
    steps: parseSteps(rec.steps),
    llmCallCount: rec.llmCallCount ?? 0,
    startTimeMs: 0,
    totalLatencyMs: rec.totalLatencyMs ?? undefined,
  }
}

/** 按关联的 assistant 消息 id 查轨迹（对话页气泡「查看轨迹」用），无关联返回 null。 */
export async function getAgentTraceByMessage(messageId: number): Promise<AgentTraceRecord | null> {
  const { data } = await http.get<AgentTraceRecord | null>('/agent/traces/by-message', { params: { messageId } })
  return data
}

export async function listAgentTraces(opts: {
  page?: number
  size?: number
  paradigm?: string
  keyword?: string
}): Promise<AgentTracePage> {
  const { data } = await http.get<AgentTracePage>('/agent/traces', { params: opts })
  return data
}

export async function getAgentTrace(id: number): Promise<AgentTraceRecord> {
  const { data } = await http.get<AgentTraceRecord>(`/agent/traces/${id}`)
  return data
}

export async function agentTraceStats(): Promise<AgentTraceStat[]> {
  const { data } = await http.get<AgentTraceStat[]>('/agent/traces/stats')
  return data
}
