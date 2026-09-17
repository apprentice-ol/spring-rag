<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'
import {
  SendOutlined, UserOutlined, RobotOutlined,
  VerticalAlignBottomOutlined, MenuUnfoldOutlined, MenuOutlined,
  ApartmentOutlined, DeploymentUnitOutlined, CopyOutlined, FileSearchOutlined,
} from '@ant-design/icons-vue'
import { streamChat, cancelChat, type AgentTrace, type Citation } from '../api/chat'
import { getAgentTraceByMessage, toAgentTrace } from '../api/agentTrace'
import { traceDetailUrl } from '../api/eval'
import { copyWithToast } from '../composables/useClipboard'
import { chatParadigmOptions, normalizeParadigm } from './evalShared'
import { messages, activeId, currentTitle, hasEarlierMessages, loadEarlierMessages, type Msg } from '../composables/useChatState'
import { useIsMobile } from '../composables/useSplitter'
import ConversationList from './ConversationList.vue'
import AgentTraceTree from './AgentTraceTree.vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

/** 桌面端：会话栏是否已收起（收起时顶部显示"会话"展开按钮） */
const props = defineProps<{ convCollapsed?: boolean }>()
const emit = defineEmits<{ toggleConv: [] }>()

function highlightCode(str: string, lang: string): string {
  if (lang && hljs.getLanguage(lang)) {
    try { return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang, ignoreIllegals: true }).value}</code></pre>` } catch { /* fallthrough */ }
  }
  const escaped = str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')
  return `<pre class="hljs"><code>${escaped}</code></pre>`
}

const md = new MarkdownIt({ html:true, linkify:true, typographer:true, breaks:true, highlight:highlightCode })

function renderMarkdown(text:string): string { return text?md.render(text):'' }

// ── 引用溯源渲染 ──
/** 该消息正文中的 [N] 角标（仅保留 citations 里真实存在的 ref，防 LLM 幻觉编号） */
function usedCitations(m: Msg): Citation[] {
  if (!m.citations?.length) return []
  return m.citations.filter(c => new RegExp(`\\[${c.ref}\\]`).test(m.content))
}

/**
 * 渲染引用角标：markdown 输出后做后处理，兼容两种来源格式——
 * ① 提示词规定的 `[N](#cite-N)` 链接式（markdown 渲染成 <a href="#cite-N">N</a>）
 * ② 模型偶发输出的裸 `[N]` 文本
 * 只替换与 citations 匹配的编号（防幻觉编号），注入内容为纯数字，无 XSS 面。
 */
function renderWithCitations(m: Msg): string {
  const html = renderMarkdown(m.content)
  if (!m.citations?.length) return html
  const refs = new Set(m.citations.map(c => c.ref))
  // ① 链接式：<a href="#cite-N">N</a> → 角标
  const linked = html.replace(/<a href="#cite-(\d{1,2})">\1<\/a>/g, (whole, num: string) => {
    return refs.has(Number(num)) ? makeBadge(Number(num)) : whole
  })
  // ② 裸 [N]（sup 标签内无方括号，二次替换不会误伤）
  return linked.replace(/\[(\d{1,2})\]/g, (whole, num: string) => {
    return refs.has(Number(num)) ? makeBadge(Number(num)) : whole
  })
}

function makeBadge(ref: number): string {
  return `<sup class="cite-badge" data-cite-ref="${ref}">${ref}</sup>`
}

/** 角标点击（事件委托）：打开右侧引用溯源抽屉并高亮对应来源 */
const citeDrawerOpen = ref(false)
const citeDrawerMsg = ref<Msg | null>(null)
const activeCiteRef = ref<number | null>(null)

function openCitations(m: Msg, ref?: number) {
  if (!usedCitations(m).length) return
  citeDrawerMsg.value = m
  activeCiteRef.value = ref ?? activeCiteRef.value ?? null
  citeDrawerOpen.value = true
}

function onBubbleClick(e: MouseEvent, m: Msg) {
  const target = (e.target as HTMLElement).closest('sup.cite-badge')
  if (!target) return
  const ref = Number(target.getAttribute('data-cite-ref'))
  if (!m.citations?.some(c => c.ref === ref)) return
  openCitations(m, ref)
}

/** 引用原文入口：优先进入项目内文档预览页（代理取源文件，本地/Docker/服务器均可用），无 docId 时退回对象存储直链 */
function citationViewUrl(c: Citation): string {
  if (c.docId) return `#/preview/${encodeURIComponent(c.docId)}`
  return c.sourceLocation || ''
}

const isMobile = useIsMobile()

const input = ref('')
const sending = ref(false)
/**
 * 当前 agent 范式（'' = 自动档：不带 agent 参数，后端意图识别路由）。
 * localStorage 旧值迁移：knowledge 是历史默认值（无法证明用户主动选择过）→ 归一为自动；
 * naive/react 旧值经 normalizeParadigm 映射新范式后保留为显式选择。
 */
const agent = ref(migrateAgentChoice(localStorage.getItem('rag_chat_agent')))
watch(agent, (v) => {
  if (v) localStorage.setItem('rag_chat_agent', v)
  else localStorage.removeItem('rag_chat_agent')
})

function migrateAgentChoice(v: string | null): string {
  if (!v || v === 'knowledge') return '' // 自动档（历史默认值，非显式选择）
  return normalizeParadigm(v)
}
const logRef = ref<HTMLElement>()
const inputRef = ref<{ focus: () => void } | null>(null)
/** composer 聚焦态（卡片描边 + 聚焦环） */
const composerFocused = ref(false)
/** 移动端：左侧会话列表抽屉开关 */
const mobileConvOpen = ref(false)

const title = computed(() => currentTitle())

// 切换会话后：滚动到底、新对话聚焦输入框
watch(activeId, async () => {
  await nextTick()
  forceScrollToBottom()
  if (!sending.value) inputRef.value?.focus()
})

// ── 发送/停止 ──
/** 本轮流式的 abort 控制器（停止生成 = abort 本地流 + 服务端取消双保险） */
let abortCtl: AbortController | null = null
/** 用户主动停止标记：onDone 据此在气泡尾部标「已停止生成」 */
const stopping = ref(false)

async function send() {
  const q = input.value.trim()
  if (!q || sending.value) return

  messages.value.push({role:'user',content:q})
  messages.value.push({role:'assistant',content:'',streaming:true})
  const ans:Msg = messages.value[messages.value.length - 1] as Msg
  input.value = ''
  sending.value = true
  stopping.value = false
  abortCtl = new AbortController()
  forceScrollToBottom()

  let pending = ''
  let contentRaf = 0
  function flushPending(){ if(pending){ ans.content+=pending; pending='' }; contentRaf=0 }

  await streamChat(q, activeId.value, {
    onContent:(chunk)=>{
      pending+=chunk
      const full=ans.content+pending
      const fenceCount=(full.match(/^```/gm)||[]).length
      if (fenceCount%2===1) return // 代码块内继续缓冲
      if (!contentRaf) contentRaf=requestAnimationFrame(()=>flushPending())
      scheduleScroll()
    },
    onTrace:(t)=>{ ans.trace = t },
    // 引用溯源映射（流式开始前一次）：ref → 文档信息。缺了这个 handler，citations 事件被
    // 静默丢弃 → 新回答无 [N] 角标、无「引用」按钮（历史消息走 DB 解析不受影响）——
    // 2026-09-11 修复：3a72fc7 重构时丢失
    onCitations:(cs)=>{ ans.citations = cs },
    onClarify:(c)=>{ ans.clarify = c },
    onMeta:(meta)=>{
      if (meta.messageId != null) ans.id = meta.messageId
      if (meta.traceId) ans.traceId = meta.traceId
    },
    onError:()=>{ flushPending(); ans.streaming=false; ans.ts=Date.now(); ans.content+='\n\n> **生成失败**'; sending.value=false },
    onDone:()=>{
      flushPending(); ans.streaming=false; ans.ts=Date.now()
      if (stopping.value) {
        // 停止生成（本地 abort 或服务端取消）：保留已生成部分并标记
        ans.content = ans.content.trim()
          ? ans.content + '\n\n> *已停止生成*'
          : '> *已停止生成*'
        stopping.value = false
      }
      sending.value = false; scrollToBottomIfStuck()
    },
  }, agent.value, abortCtl.signal)
}

/** 停止生成：立即 abort 本地流（反馈即时），再通知服务端（dispose 计费 + 部分回答落库） */
async function stop() {
  if (!sending.value) return
  stopping.value = true
  abortCtl?.abort()
  try { await cancelChat(activeId.value) } catch { /* 会话可能已自然结束 */ }
}

// ── 消息轨迹回看（assistant 气泡「轨迹」入口：本轮内存态 / 历史按 messageId 拉取） ──
const traceDrawerOpen = ref(false)
const drawerTrace = ref<AgentTrace | null>(null)
const traceLoading = ref(false)

async function openTrace(m: Msg) {
  traceDrawerOpen.value = true
  if (m.trace) { drawerTrace.value = m.trace; return }
  drawerTrace.value = null
  if (!m.id) return
  traceLoading.value = true
  try {
    const rec = await getAgentTraceByMessage(m.id)
    if (rec) {
      drawerTrace.value = toAgentTrace(rec)
      m.trace = drawerTrace.value
      // 历史消息顺带补 traceId 与时间——「链路」按钮随之可用；ts 用轨迹 createTime
      // （后端带时区偏移序列化，new Date 解析不受浏览器时区影响，OO 深链窗口不错位）
      if (!m.traceId && rec.traceId) m.traceId = rec.traceId
      if (rec.createTime) m.ts = new Date(rec.createTime).getTime()
    }
  } catch {
    /* 保持空态 */
  } finally {
    traceLoading.value = false
  }
}

// ── OpenObserve 深链（eval.ts 模块级缓存模板；按消息时间生成 ±10min 查询窗口） ──
function openObsLink(m: Msg) {
  if (!m.traceId) return
  const url = traceDetailUrl(m.traceId, m.ts)
  if (url) window.open(url, '_blank')
}

/** 消息时间（操作条右侧）：HH:mm */
function fmtClock(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

// ── 滚动跟随 ──
const stickToBottom = ref(true)
const SCROLL_THRESHOLD = 80  // 距底部小于此值视为“在底部”

function onLogScroll() {
  const el = logRef.value
  if (!el) return
  const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  stickToBottom.value = distFromBottom < SCROLL_THRESHOLD
}

/** 流式增量滚动：仅当用户粘在底部时跟随；用户往上滚了就不打扰 */
function scrollToBottomIfStuck() {
  if (stickToBottom.value && logRef.value) {
    logRef.value.scrollTop = logRef.value.scrollHeight
  }
}

/** 强制滚到底并恢复跟随（切会话 / 发消息 / 点“回到底部”时用） */
function forceScrollToBottom() {
  stickToBottom.value = true
  if (logRef.value) logRef.value.scrollTop = logRef.value.scrollHeight
}

let scrollRaf=0
function scheduleScroll(){ if(scrollRaf)return; scrollRaf=requestAnimationFrame(()=>{ scrollToBottomIfStuck(); scrollRaf=0 }) }

// ── 向上加载更早消息（消息列表 id 游标分页配合）──
const loadingEarlier = ref(false)
async function onLoadEarlier() {
  if (loadingEarlier.value) return
  const el = logRef.value
  const prevHeight = el ? el.scrollHeight : 0
  loadingEarlier.value = true
  try {
    await loadEarlierMessages()
    // 保持视口锚在原首条消息：补偿加载新增的高度
    if (el) el.scrollTop += el.scrollHeight - prevHeight
  } finally {
    loadingEarlier.value = false
  }
}

// ── 空对话引导 ──
/** 引导建议（贴合系统两大能力：知识库问答带引用溯源 / 运维诊断自动追问后排查） */
const SUGGESTIONS = [
  '发票冲红的完整办理流程是什么？',
  '专用发票和普通发票的抵扣规则有什么区别？',
  '生产环境开票接口偶发超时，帮我排查一下',
]

/**
 * 引导建议回填输入框（不直接发送）：用户多半要补充信息再发——
 * 诊断类问题需补环境/报错细节，知识类问题常要限定范围，直接发出去只会得到追问。
 */
function fillSuggestion(s: string) {
  input.value = s
  nextTick(() => {
    inputRef.value?.focus()
    // 光标移到末尾，接着补充即可（$el 即 textarea 本体；非 textarea 元素无此方法，防御跳过）
    const el = (inputRef.value as unknown as { $el?: HTMLTextAreaElement })?.$el
    if (el && typeof el.setSelectionRange === 'function') el.setSelectionRange(s.length, s.length)
  })
}
</script>

<template>
  <div class="chat">
    <!-- 移动端会话列表抽屉：遮罩 -->
    <transition name="fade">
      <div v-if="isMobile && mobileConvOpen" class="conv-drawer-overlay" @click="mobileConvOpen = false" />
    </transition>
    <!-- 移动端会话列表抽屉：左侧滑出 -->
    <transition name="slide-left">
      <div v-if="isMobile && mobileConvOpen" class="conv-drawer">
        <ConversationList @chat="mobileConvOpen = false" />
      </div>
    </transition>

    <!-- 移动端迷你顶栏：会话抽屉按钮 + 当前会话标题 -->
    <div v-if="isMobile" class="chat-mini-header">
      <a-button type="text" size="small" class="mini-conv-btn" title="对话列表" @click="mobileConvOpen = true">
        <template #icon><MenuOutlined /></template>
        <span class="mini-conv-label">对话</span>
      </a-button>
      <span class="mini-title">{{ title }}</span>
    </div>

    <!-- 桌面端会话栏收起时：顶栏显示"会话"展开按钮 -->
    <div v-if="!isMobile && props.convCollapsed" class="conv-toggle-bar">
      <a-button type="text" size="small" class="conv-toggle-btn" @click="emit('toggleConv')">
        <template #icon><MenuUnfoldOutlined /></template>
        <span class="conv-toggle-label">会话</span>
      </a-button>
    </div>

    <!-- 消息列表 -->
    <div ref="logRef" class="log" @scroll="onLogScroll">
      <div v-if="hasEarlierMessages" class="load-earlier">
        <a-button type="link" size="small" :loading="loadingEarlier" @click="onLoadEarlier">加载更早消息</a-button>
      </div>
      <div v-if="!messages.length" class="empty">
        <div class="empty-mark" aria-hidden="true">
          <!-- 品牌签名：对角引用括号 + 圆点（回答带出处） -->
          <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M4.5 9.5V6.3c0-1 .8-1.8 1.8-1.8h3.2" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
            <path d="M19.5 14.5v3.2c0 1-.8 1.8-1.8 1.8h-3.2" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
            <circle cx="12" cy="12" r="2.5" fill="currentColor" />
          </svg>
        </div>
        <h4 class="empty-title">开始对话</h4>
        <p class="empty-desc">知识库问答（回答附原文引用） · 运维诊断（自动追问补齐信息后排查）</p>
        <div class="empty-suggestions">
          <a-tag
            v-for="s in SUGGESTIONS"
            :key="s" class="suggestion-tag" @click="fillSuggestion(s)"
          >{{ s }}</a-tag>
        </div>
        <p class="empty-hint">点击问题填入输入框，可补充细节后发送</p>
      </div>

      <div v-for="(m,i) in messages" :key="i" class="msg" :class="m.role">
        <div v-if="m.role==='assistant'" class="avatar avatar-ai"><RobotOutlined /></div>
        <div class="bubble">
          <div v-if="m.streaming&&!m.content" class="typing-indicator"><span></span><span></span><span></span></div>
          <div v-else-if="m.role==='user'" class="msg-text user-text">{{ m.content }}</div>
          <div v-else class="markdown-body" v-html="renderWithCitations(m)" @click="onBubbleClick($event, m)"></div>
          <!-- 运维诊断追问卡片：缺失槽位一次问齐（clarify 事件；用户在输入框补充回答即可续跑） -->
          <div v-if="m.clarify" class="clarify-card">
            <div v-for="(q,qi) in m.clarify.questions" :key="qi" class="clarify-item">
              <span class="clarify-q">{{ qi+1 }}. {{ q.question }}</span>
              <a-tag v-if="q.hint" color="blue" class="clarify-hint">{{ q.hint }}</a-tag>
            </div>
          </div>
          <span v-if="m.streaming&&m.content" class="stream-cursor"></span>
          <!-- 操作条：轨迹回看 + 引用溯源 + traceId（chip 可复制）+ OpenObserve 全链路（流式结束后） -->
          <div v-if="m.role==='assistant' && !m.streaming && (m.id || m.trace)" class="msg-actions">
            <button class="action-btn" @click="openTrace(m)">
              <ApartmentOutlined />轨迹
            </button>
            <button v-if="usedCitations(m).length" class="action-btn" @click="openCitations(m)">
              <FileSearchOutlined />引用 {{ usedCitations(m).length }}
            </button>
            <template v-if="m.traceId">
              <span class="trace-chip" :title="'traceId: ' + m.traceId">{{ m.traceId }}</span>
              <button class="action-btn" title="复制 traceId" @click="copyWithToast(m.traceId)">
                <CopyOutlined />
              </button>
              <button class="action-btn" title="查看 OpenObserve 完整链路" @click="openObsLink(m)">
                <DeploymentUnitOutlined />链路
              </button>
            </template>
            <span v-if="m.ts" class="msg-time num">{{ fmtClock(m.ts) }}</span>
          </div>
        </div>
        <div v-if="m.role==='user'" class="avatar avatar-user"><UserOutlined /></div>
      </div>
      <div class="log-bottom"></div>
    </div>

    <!-- 回到底部（用户往上滚时出现） -->
    <transition name="fade">
      <button v-if="!stickToBottom" class="scroll-to-bottom" title="回到底部" @click="forceScrollToBottom">
        <VerticalAlignBottomOutlined />
      </button>
    </transition>

    <!-- 输入区：卡片式 composer（聚焦描边 + 多行 + 底部工具行） -->
    <div class="composer">
      <div class="composer-card" :class="{ focused: composerFocused }">
        <a-textarea
          ref="inputRef"
          v-model:value="input"
          placeholder="输入问题，基于知识库提问…"
          :disabled="sending"
          :auto-size="{ minRows: 1, maxRows: 6 }"
          variant="borderless"
          class="composer-input"
          @keydown.enter.exact.prevent="send"
          @focus="composerFocused = true"
          @blur="composerFocused = false"
        />
        <div class="composer-foot">
          <div class="composer-paradigm">
            <span class="bar-label">范式</span>
            <a-select v-model:value="agent" size="small" class="bar-select" :disabled="sending" :popup-match-select-width="false">
              <a-select-option v-for="p in chatParadigmOptions()" :key="p.value || 'auto'" :value="p.value">
                {{ p.label }} · {{ p.desc }}
              </a-select-option>
            </a-select>
          </div>
          <span class="composer-hint">Enter 发送 · Shift+Enter 换行</span>
          <!-- 同一按钮：空闲=主色发送，生成中=红色描边停止（实心方块符号；停止态不可带 loading——antd loading 按钮不可点击） -->
          <a-button v-if="sending" danger class="composer-btn composer-stop" title="停止生成" @click="stop">
            <span class="stop-square"></span>
          </a-button>
          <a-button v-else type="primary" :disabled="!input.trim()" class="composer-btn" @click="send">
            <template #icon><SendOutlined /></template>
          </a-button>
        </div>
      </div>
    </div>

    <!-- 轨迹回看抽屉（复用 Agent 对照面板的轨迹树） -->
    <a-drawer
      :open="traceDrawerOpen"
      :width="560"
      title="Agent 执行轨迹"
      @update:open="(v: boolean) => (traceDrawerOpen = v)"
    >
      <a-spin :spinning="traceLoading">
        <AgentTraceTree v-if="drawerTrace" :trace="drawerTrace" />
        <a-empty v-else-if="!traceLoading" description="该消息无 agent 轨迹（闲聊/诊断分支，或早于本功能的数据）" />
      </a-spin>
    </a-drawer>

    <!-- 引用溯源抽屉：点击正文 [N] 角标或操作条「引用」打开，展示该回答被引用的来源文档 -->
    <a-drawer
      :open="citeDrawerOpen"
      :width="440"
      title="引用溯源"
      @update:open="(v: boolean) => (citeDrawerOpen = v)"
    >
      <template v-if="citeDrawerMsg">
        <div class="cite-drawer-hint">
          回答正文中带 <sup class="cite-badge cite-badge-inline">N</sup> 角标的内容来自下列来源（共
          {{ usedCitations(citeDrawerMsg).length }} 份，按引用顺序排列）
        </div>
        <div
          v-for="c in usedCitations(citeDrawerMsg)"
          :key="c.ref"
          class="cite-item"
          :class="{ active: activeCiteRef === c.ref }"
          @click="activeCiteRef = activeCiteRef === c.ref ? null : c.ref"
        >
          <span class="cite-badge-static">[{{ c.ref }}]</span>
          <div class="cite-main">
            <div class="cite-doc">
              <span class="cite-doc-name" :title="c.docName">{{ c.docName }}</span>
            </div>
            <a
              v-if="citationViewUrl(c)"
              class="cite-source-link"
              :href="citationViewUrl(c)"
              target="_blank"
              rel="noopener"
            >查看原文 ↗</a>
          </div>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.chat { flex:1; display:flex; flex-direction:column; min-width:0; position:relative; height:100%; }

/* 移动端迷你顶栏 */
.chat-mini-header {
  display:flex; align-items:center; gap:4px;
  padding:8px 10px;
  border-bottom:1px solid var(--color-border-light);
  background:var(--color-surface);
  flex-shrink:0;
}
.mini-conv-btn { color: var(--color-ink-secondary); flex-shrink:0; }
.mini-conv-btn:hover { color: var(--color-primary); }
.mini-conv-label { font-size:13px; margin-left:2px; }
.mini-title { font-size:14px; font-weight:600; color:var(--color-ink); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }

/* 移动端会话列表抽屉 */
.conv-drawer {
  position:absolute; top:0; bottom:0; left:0;
  width:78vw; max-width:300px;
  background:var(--color-surface);
  box-shadow:4px 0 24px rgba(0,0,0,0.12);
  z-index:120;
}
.conv-drawer-overlay { position:absolute; inset:0; background:rgba(0,0,0,0.35); z-index:110; }
.slide-left-enter-active, .slide-left-leave-active { transition: transform .25s ease; }
.slide-left-enter-from, .slide-left-leave-to { transform: translateX(-100%); }

/* 桌面端会话栏收起时的展开按钮条 */
.conv-toggle-bar {
  display:flex; align-items:center;
  padding:4px 10px;
  border-bottom:1px solid var(--color-border-light);
  background:var(--color-surface);
  flex-shrink:0;
}
.conv-toggle-btn { color:var(--color-ink-secondary); }
.conv-toggle-btn:hover { color:var(--color-primary); }
.conv-toggle-label { font-size:13px; margin-left:2px; }

.scroll-to-bottom { position:absolute; right:24px; bottom:96px; width:36px; height:36px; border-radius:50%; background:var(--color-surface); border:1px solid var(--color-border); box-shadow:0 2px 10px rgba(0,0,0,.12); display:flex; align-items:center; justify-content:center; cursor:pointer; color:var(--color-primary); z-index:50; transition:background .15s; }
.scroll-to-bottom:hover { background:var(--color-surface-secondary); }
.fade-enter-active, .fade-leave-active { transition:opacity .2s; }
.fade-enter-from, .fade-leave-to { opacity:0; }
.log { flex:1; overflow-y:auto; padding:20px 24px; display:flex; flex-direction:column; gap:16px; }
.log-bottom { height:4px; flex-shrink:0; }

.load-earlier { display:flex; justify-content:center; flex-shrink:0; }
/* 空状态：点阵台面（边缘渐隐）+ 品牌签名插图 */
.empty {
  flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center;
  text-align:center; padding:40px 20px; border-radius:var(--radius-xl);
  background-image: radial-gradient(circle, rgba(22, 26, 30, 0.06) 1px, transparent 1px);
  background-size: 20px 20px;
  -webkit-mask-image: radial-gradient(ellipse at center, #000 45%, transparent 80%);
  mask-image: radial-gradient(ellipse at center, #000 45%, transparent 80%);
}
.empty-mark {
  width:72px; height:72px; display:flex; align-items:center; justify-content:center;
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-hover));
  color:#fff; border-radius:20px; margin-bottom:16px;
  box-shadow: 0 10px 28px rgba(0, 100, 250, 0.28);
}
.empty-mark svg { width:36px; height:36px; }
.empty-title { margin:0 0 6px; font-size:16px; font-weight:600; }
.empty-desc { margin:0 0 16px; font-size:13px; color:var(--color-ink-secondary); }
.empty-suggestions { display:flex; flex-wrap:wrap; gap:6px; justify-content:center; }
.suggestion-tag { cursor:pointer; user-select:none; transition: color .15s, background .15s, border-color .15s; }
.suggestion-tag:hover { color:var(--color-primary); background:var(--color-primary-light); border-color:var(--color-primary); }
.empty-hint { margin:10px 0 0; font-size:11.5px; color:var(--color-ink-tertiary); }

.msg { display:flex; gap:10px; align-items:flex-start; max-width:85%; }
.msg.user { align-self:flex-end; flex-direction:row-reverse; }
.avatar { width:28px; height:28px; border-radius:50%; display:flex; align-items:center; justify-content:center; font-size:13px; flex-shrink:0; }
.avatar-ai { background:var(--color-primary); color:#fff; }
.avatar-user { background:var(--color-surface-secondary); color:var(--color-ink-secondary); }
.bubble { padding:10px 14px; border-radius:var(--radius-lg); font-size:14px; line-height:1.6; min-width:36px; }
.msg.assistant .bubble { background:var(--color-surface); border:1px solid var(--color-border); border-top-left-radius:4px; }
.msg.user .bubble { background:var(--color-primary); color:#fff; border-top-right-radius:4px; }
.user-text { white-space:pre-wrap; word-break:break-word; }
.stream-cursor { color:var(--color-signal); animation:blink .9s step-end infinite; font-weight:bold; }
@keyframes blink { 50%{opacity:0} }
.typing-indicator { display:flex; gap:4px; padding:4px 0; }
.typing-indicator span { width:6px; height:6px; border-radius:50%; background:var(--color-ink-tertiary); animation:typing-bounce 1.4s ease-in-out infinite; }
.typing-indicator span:nth-child(2){animation-delay:.2s}
.typing-indicator span:nth-child(3){animation-delay:.4s}
@keyframes typing-bounce { 0%,60%,100%{transform:translateY(0);opacity:.4} 30%{transform:translateY(-5px);opacity:1} }

/* 气泡操作条（轨迹 / traceId / OO 链路） */
/* ── 引用溯源 ── */
/* v-html 注入的角标不带 scoped 属性，必须用 :deep 才能命中 */
/* 纯蓝色上标数字（无边框）：加粗 + 主色保证辨识度，hover 下划线示意可点击 */
:deep(.cite-badge) { display:inline; margin:0 1px; color:var(--color-primary, #0064fa); font-size:11px; font-weight:700; cursor:pointer; vertical-align:super; line-height:1; user-select:none; transition:color .12s ease; }
:deep(.cite-badge:hover) { text-decoration:underline; }
.cite-drawer-hint { font-size:12px; color:var(--color-ink-secondary); background:var(--color-surface-secondary); border-radius:6px; padding:8px 10px; margin-bottom:12px; line-height:1.7; }
.cite-drawer-hint .cite-badge-inline { vertical-align:baseline; margin:0 1px; }
.cite-item { display:flex; gap:8px; padding:8px 10px; border-radius:8px; cursor:pointer; border:1px solid transparent; transition:background .15s, border-color .15s; }
.cite-item:hover { background:var(--color-surface-secondary); }
.cite-item.active { background:var(--color-primary-light, #e8f1ff); border-color:var(--color-primary, #0064fa); }
.cite-badge-static { flex-shrink:0; color:var(--color-primary, #0064fa); font-size:12px; font-weight:700; line-height:20px; }
.cite-main { flex:1; min-width:0; }
.cite-doc { display:flex; align-items:center; gap:8px; }
.cite-doc-name { font-size:12px; font-weight:500; color:var(--color-ink); overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cite-chunk-count { flex-shrink:0; font-size:11px; color:var(--color-ink-tertiary); }
.cite-preview { margin-top:4px; font-size:12px; color:var(--color-ink-secondary); line-height:1.6; display:-webkit-box; -webkit-line-clamp:4; -webkit-box-orient:vertical; overflow:hidden; }
.cite-source-link { flex-shrink:0; font-size:11px; margin-top:4px; display:inline-block; }
.msg-actions { display:flex; align-items:center; gap:2px; flex-wrap:wrap; margin-top:8px; padding-top:6px; border-top:1px dashed var(--color-border-light); }
/* 运维诊断追问卡片（clarify 事件）：缺失槽位清单 + 取值提示 */
.clarify-card { margin-top:10px; padding:10px 12px; background:var(--color-primary-light); border:1px solid var(--color-border-light); border-radius:var(--radius-md); }
.clarify-item { display:flex; align-items:center; gap:8px; flex-wrap:wrap; padding:3px 0; font-size:13px; color:var(--color-ink); }
.clarify-q { font-weight:500; }
.clarify-hint { font-size:11px; }
.action-btn { display:inline-flex; align-items:center; gap:4px; border:none; background:none; padding:2px 8px; font-size:12px; color:var(--color-ink-tertiary); cursor:pointer; border-radius:var(--radius-sm); transition:color .15s, background .15s; }
.action-btn:hover { color:var(--color-primary); background:var(--color-primary-light); }
/* traceId chip：等宽缩略展示（悬停 title 看全量，旁边复制按钮取全文） */
.trace-chip {
  font-family: var(--font-display);
  font-size: 10.5px;
  line-height: 18px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 0 8px;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  user-select: all;
  -webkit-user-select: all;
}
.msg-time { margin-left:auto; font-size:11px; color:var(--color-ink-tertiary); flex-shrink:0; }

.markdown-body { font-family:var(--font-body); font-size:14px; line-height:1.7; color:var(--color-ink); background:transparent; }
.markdown-body :deep(pre){ background:var(--color-surface-secondary)!important; border-radius:var(--radius-md); padding:10px 14px!important; overflow-x:auto; font-size:13px; border:1px solid var(--color-border-light); }
.markdown-body :deep(code:not(pre code)){ font-family:var(--font-display); font-size:13px; background:var(--color-surface-secondary); padding:1px 4px; border-radius:3px; }
.markdown-body :deep(pre code){ background:transparent!important; padding:0!important; }
.markdown-body :deep(p){ margin:0 0 6px; }
.markdown-body :deep(table){
  border-collapse:separate; border-spacing:0; margin:8px 0; width:100%; font-size:13px;
  border:1px solid var(--color-border-light); border-radius:var(--radius-md); overflow:hidden;
}
.markdown-body :deep(th),.markdown-body :deep(td){ border-bottom:1px solid var(--color-border-light); padding:6px 10px; text-align:left; }
.markdown-body :deep(tr:last-child td),
.markdown-body :deep(thead tr:last-child th){ border-bottom:none; }
.markdown-body :deep(th){ background:var(--color-surface-secondary); font-weight:600; border-bottom:1px solid var(--color-border); }
.markdown-body :deep(tbody tr:hover td){ background:var(--color-hover-bg); }
.markdown-body :deep(a){ color:var(--color-primary); }

/* 输入区：卡片式 composer */
.composer { padding:12px 20px 14px; border-top:1px solid var(--color-border); background:var(--color-surface); flex-shrink:0; }
.composer-card {
  border:1px solid var(--color-border);
  border-radius:var(--radius-xl);
  background:var(--color-surface);
  padding:10px 12px 8px 16px;
  transition:border-color .15s, box-shadow .15s;
}
.composer-card.focused { border-color:var(--color-primary); box-shadow:0 0 0 3px var(--color-focus-ring); }
.composer-input :deep(textarea) { font-size:14px; line-height:1.6; padding:4px 0; }
.composer-foot { display:flex; align-items:center; gap:10px; margin-top:8px; }
.composer-paradigm { display:flex; align-items:center; gap:8px; min-width:0; }
.bar-label { font-family:var(--font-display); font-size:10px; font-weight:500; letter-spacing:0.08em; text-transform:uppercase; color:var(--color-ink-tertiary); flex-shrink:0; }
.bar-select { width:200px; }
.composer-hint { margin-left:auto; font-size:11px; color:var(--color-ink-tertiary); white-space:nowrap; }
.composer-btn { width:34px; height:34px; border-radius:50%; flex-shrink:0; }
/* 停止态：红色描边圆钮 + 实心方块停止符号（danger 变体，方块 currentColor 跟随红色，hover 红底） */
.composer-stop { display:inline-flex; align-items:center; justify-content:center; padding:0; }
.stop-square { width:10px; height:10px; border-radius:2px; background:currentColor; display:inline-block; }

@media (max-width: 768px) {
  .log { padding:14px 12px; gap:12px; }
  .msg { max-width:92%; }
  .composer { padding:8px 10px; padding-bottom: calc(8px + env(safe-area-inset-bottom)); }
  .composer-card { padding:8px 10px 6px 12px; }
  .composer-hint { display:none; }
  .bar-select { width:150px; }
}
</style>
