import { ref } from 'vue'
import {
  listConversations, getMessages, deleteConversation,
  type ConversationItem,
} from '../api/chat'

/**
 * 会话共享状态（模块级 ref）：ConversationList 与 ChatView 分处两个 tab，
 * 通过同一份状态天然同步——列表里切会话，聊天视图立即跟随。
 */

export interface Msg {
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
}

export const conversations = ref<ConversationItem[]>([])
export const activeId = ref('')
export const messages = ref<Msg[]>([])

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

async function loadMessages(convId: string) {
  try {
    const msgs = await getMessages(convId)
    messages.value = msgs.map(m => ({ role: m.role, content: m.content }))
  } catch {
    messages.value = []
  }
}

export async function loadConversations() {
  try {
    conversations.value = await listConversations()
  } catch {
    conversations.value = []
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
