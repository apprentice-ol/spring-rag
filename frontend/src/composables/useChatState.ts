import { ref } from 'vue'
import {
  listConversations, getMessages, deleteConversation,
  type ConversationItem, type AgentTrace, type Citation,
} from '../api/chat'

/**
 * 会话共享状态（模块级 ref）：ConversationList 与 ChatView 分处两个 tab，
 * 通过同一份状态天然同步——列表里切会话，聊天视图立即跟随。
 */

export interface Msg {
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
  /** 后端 sa_message.id（meta 事件/历史加载获得；历史消息按它查 agent 轨迹） */
  id?: number
  /** 本次请求 OTel traceId（meta 事件获得；跳 OpenObserve 全链路） */
  traceId?: string
  /** 消息时间（ms；OO 深链查询窗口用） */
  ts?: number
  /** 本轮 SSE trace 事件携带的 agent 轨迹（仅本次会话内存态；历史消息走 by-message 接口拉取） */
  trace?: AgentTrace
  /** 引用溯源（SSE citations 事件/历史加载获得；正文 [N] 角标据此渲染） */
  citations?: Citation[]
}

export const conversations = ref<ConversationItem[]>([])
export const activeId = ref('')
export const messages = ref<Msg[]>([])
/** 会话列表分页状态（ConversationList「加载更多」用） */
export const conversationTotal = ref(0)
const convPageSize = 20
let convPage = 1

export function genId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = Math.random() * 16 | 0
    return (c === 'x' ? r : r & 0x3 | 0x8).toString(16)
  })
}

const ACTIVE_KEY = 'rag_active_conv'

/** 初始化：恢复上次会话并加载列表（App 挂载时调用一次） */
export async function initChatState() {
  activeId.value = localStorage.getItem(ACTIVE_KEY) || ''
  await loadConversations()
  if (activeId.value) {
    await loadMessages(activeId.value)
  } else if (conversations.value.length > 0) {
    await selectConversation(conversations.value[0].conversationId)
  }
}

/** 消息条数已到最早时置 false（「加载更早」入口隐藏用） */
export const hasEarlierMessages = ref(false)

function toMsg(m: {
  id: number
  role: 'user' | 'assistant'
  content: string
  createdAt: string
  citations?: string | null
  traceId?: string | null
}): Msg {
  let citations: Citation[] | undefined
  if (m.citations) {
    try {
      citations = JSON.parse(m.citations)
    } catch {
      /* 损坏的引用 JSON 忽略 */
    }
  }
  return {
    id: m.id,
    role: m.role,
    content: m.content,
    ts: m.createdAt ? new Date(m.createdAt).getTime() : undefined,
    citations,
    traceId: m.traceId || undefined,
  }
}

async function loadMessages(convId: string) {
  try {
    // 首屏只取最近 50 条（升序），更早的走「向上加载」
    const msgs = await getMessages(convId)
    messages.value = msgs.map(toMsg)
    hasEarlierMessages.value = msgs.length >= 50
  } catch {
    messages.value = []
    hasEarlierMessages.value = false
  }
}

/** 向上加载更早一页消息（前插），返回是否加载成功 */
export async function loadEarlierMessages(): Promise<boolean> {
  const oldestId = messages.value.find(m => m.id != null)?.id
  if (!activeId.value || oldestId == null) return false
  try {
    const earlier = await getMessages(activeId.value, oldestId)
    if (earlier.length > 0) {
      messages.value = [...earlier.map(toMsg), ...messages.value]
    }
    hasEarlierMessages.value = earlier.length >= 50
    return earlier.length > 0
  } catch {
    return false
  }
}

export async function loadConversations() {
  try {
    const page = await listConversations(1, convPageSize)
    conversations.value = page.records
    conversationTotal.value = page.total
    convPage = 1
  } catch {
    conversations.value = []
    conversationTotal.value = 0
  }
}

/** 会话列表「加载更多」（下一页追加） */
export async function loadMoreConversations(): Promise<boolean> {
  if (conversations.value.length >= conversationTotal.value) return false
  try {
    const page = await listConversations(convPage + 1, convPageSize)
    convPage += 1
    conversations.value = [...conversations.value, ...page.records]
    conversationTotal.value = page.total
    return page.records.length > 0
  } catch {
    return false
  }
}

export async function selectConversation(convId: string) {
  activeId.value = convId
  localStorage.setItem(ACTIVE_KEY, convId)
  await loadMessages(convId)
}

export function newConversation() {
  activeId.value = genId()
  localStorage.setItem(ACTIVE_KEY, activeId.value)
  messages.value = []
  loadConversations() // 后台刷新列表（等第一条消息时自动创建）
}

export async function removeConversation(convId: string) {
  try { await deleteConversation(convId) } catch { /* 忽略 */ }
  if (activeId.value === convId) {
    activeId.value = ''
    messages.value = []
    const next = conversations.value.find(c => c.conversationId !== convId)
    if (next) {
      await selectConversation(next.conversationId)
    } else {
      activeId.value = genId()
      localStorage.setItem(ACTIVE_KEY, activeId.value)
    }
  }
  await loadConversations()
}

/** 当前会话标题（供移动端聊天页头部展示；新对话未落库时显示"新对话"） */
export function currentTitle(): string {
  if (!activeId.value) return '新对话'
  const found = conversations.value.find(c => c.conversationId === activeId.value)
  return found?.title || '新对话'
}
