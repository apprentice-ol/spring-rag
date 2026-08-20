export interface ConversationItem {
  id: number
  conversationId: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface MessageItem {
  id: number
  conversationId: string
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  /** 引用溯源 JSON（后端 jsonb 读出的字符串；RAG 回答非空，需 JSON.parse） */
  citations?: string | null
  /** 关联 Agent 轨迹的 traceId（历史消息加载时后端批量回填，跳 OpenObserve 全链路用） */
  traceId?: string | null
}

/** 回答引用溯源项（后端 Citation record 镜像）：ref 对应正文 [N] 角标 */
export interface Citation {
  ref: number
  docId: string | null
  docName: string
  chunkCount: number
  preview: string
  sourceLocation: string | null
}

/** 通用分页返回（后端 PageResult 镜像） */
export interface PageResult<T> {
  total: number
  records: T[]
}

export async function listConversations(page = 1, size = 20): Promise<PageResult<ConversationItem>> {
  const { data } = await (await import('./client')).http.get<PageResult<ConversationItem>>(
    '/chat/conversations', { params: { page, size } },
  )
  return data
}

export async function createConversation(title?: string): Promise<ConversationItem> {
  const { data } = await (await import('./client')).http.post<ConversationItem>('/chat/conversations', { title })
  return data
}

/**
 * 会话消息（id 游标分页，按时间升序返回）。
 * 默认返回最近 limit 条；带 beforeId 返回更早一页（「向上加载更早」），返回条数小于 limit 即已到最早。
 */
export async function getMessages(conversationId: string, beforeId?: number, limit = 50): Promise<MessageItem[]> {
  const params: Record<string, number> = { limit }
  if (beforeId != null) params.beforeId = beforeId
  const { data } = await (await import('./client')).http.get<MessageItem[]>(
    `/chat/conversations/${conversationId}/messages`, { params },
  )
  return data
}

export async function deleteConversation(conversationId: string): Promise<void> {
  await (await import('./client')).http.delete(`/chat/conversations/${conversationId}`)
}

// ===== Agent 执行轨迹（后端 AgentTrace/AgentStep 的前端镜像） =====

// ReAct 三步结构化产物（对齐后端 AgentStepDetails record）
export interface ChunkHitDetail {
  ref: number
  score: number | null
  originalScore: number | null
  docName: string
  channel: string
  preview: string
}
export interface GradeRowDetail {
  ref: number
  score: number
  relevant: boolean
  reason: string
}
export interface RerankRowDetail {
  ref: number
  score: number | null
}
export interface RetrieveDetail {
  query: string
  chunks: ChunkHitDetail[]
}
export interface GradeDetail {
  verdict: string
  relevant: number
  total: number
  avgScore: number
  grades: GradeRowDetail[]
}
export interface RerankDetail {
  topN: number
  before: number[]
  after: RerankRowDetail[]
}
export type AgentStepDetail = RetrieveDetail | GradeDetail | RerankDetail

export interface AgentStep {
  stepIndex: number
  action: string
  thought: string
  inputSummary: string
  outputSummary: string
  latencyMs: number
  detail?: AgentStepDetail
}

export interface AgentTrace {
  paradigm: string
  steps: AgentStep[]
  llmCallCount: number
  startTimeMs: number
  totalLatencyMs?: number
}

/** SSE meta 事件（流末尾）：消息/链路元信息，前端绑定到 assistant 气泡（轨迹回看 + OO 深链） */
export interface StreamMeta {
  messageId: number | null
  traceId: string | null
  paradigm: string | null
}

export interface StreamHandlers {
  onContent: (chunk: string) => void
  onTrace?: (trace: AgentTrace) => void
  onCitations?: (citations: Citation[]) => void
  onMeta?: (meta: StreamMeta) => void
  onError: (err: unknown) => void
  onDone: () => void
}

/**
 * 流式问答（SSE GET）。按 SSE 规范解析：event 行决定类型（message→回答块 / trace→agent 轨迹 / meta→消息元信息），
 * 一个事件可由多个 data: 行组成，空行结束。agent 参数指定范式（naive/react）。
 */
export async function streamChat(
  question: string,
  conversationId: string,
  handlers: StreamHandlers,
  agent?: string,
): Promise<void> {
  const params = new URLSearchParams({ question, conversationId })
  if (agent) params.set('agent', agent)
  const resp = await fetch(`/api/rag/chat/stream?${params}`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
  })
  if (!resp.ok || !resp.body) {
    throw new Error(`stream failed: ${resp.status}`)
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let dataLines: string[] = []
  let currentEvent = 'message'

  const flush = () => {
    if (dataLines.length > 0) {
      let raw = dataLines.join('\n')
      // 兼容个别情况下 data 被当成 JSON 字符串传输
      if (raw.startsWith('"')) {
        try { raw = JSON.parse(raw) } catch { /* ignore */ }
      }
      if (raw) {
        if (currentEvent === 'trace') {
          // trace 事件（agent 执行轨迹）：有处理器则回调，无处理器静默丢弃，
          // 绝不混入回答内容——否则主聊天页（未传 onTrace）会把整段 trace JSON 当正文打印
          if (handlers.onTrace) {
            try {
              handlers.onTrace(JSON.parse(raw) as AgentTrace)
            } catch {
              /* 忽略损坏的 trace */
            }
          }
        } else if (currentEvent === 'citations') {
          // citations 事件（引用溯源映射，流式开始前一次）：ref → 文档信息，无处理器静默丢弃
          if (handlers.onCitations) {
            try {
              handlers.onCitations(JSON.parse(raw) as Citation[])
            } catch {
              /* 忽略损坏的 citations */
            }
          }
        } else if (currentEvent === 'meta') {
          // meta 事件（messageId/traceId/paradigm，流末尾一次）：同 trace，无处理器静默丢弃
          if (handlers.onMeta) {
            try {
              handlers.onMeta(JSON.parse(raw) as StreamMeta)
            } catch {
              /* 忽略损坏的 meta */
            }
          }
        } else {
          handlers.onContent(raw)
        }
      }
      dataLines = []
    }
    currentEvent = 'message'
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (!line) {
          flush()
        } else if (line.startsWith(':')) {
          // 注释行，忽略
        } else if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5))
        }
        // 忽略 id:/retry:
      }
    }
    flush()
    handlers.onDone()
  } catch (e) {
    handlers.onError(e)
  }
}
