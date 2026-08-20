<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'
import {
  SendOutlined, UserOutlined, RobotOutlined,
  VerticalAlignBottomOutlined, MenuUnfoldOutlined, MenuOutlined,
  ApartmentOutlined, DeploymentUnitOutlined, CopyOutlined, FileSearchOutlined,
} from '@ant-design/icons-vue'
import { streamChat, type AgentTrace, type Citation } from '../api/chat'
import { getAgentTraceByMessage, toAgentTrace } from '../api/agentTrace'
import { traceDetailUrl } from '../api/eval'
import { copyWithToast } from '../composables/useClipboard'
import { PARADIGMS } from './evalShared'
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
/** 当前 agent 范式（默认 naive，localStorage 持久化） */
const agent = ref(localStorage.getItem('rag_chat_agent') || 'naive')
watch(agent, (v) => localStorage.setItem('rag_chat_agent', v))
const logRef = ref<HTMLElement>()
const inputRef = ref<HTMLElement>()
/** 移动端：左侧会话列表抽屉开关 */
const mobileConvOpen = ref(false)

const title = computed(() => currentTitle())

// 切换会话后：滚动到底、新对话聚焦输入框
watch(activeId, async () => {
  await nextTick()
  forceScrollToBottom()
  if (!sending.value) inputRef.value?.focus()
})

// ── 发送消息 ──
async function send() {
  const q = input.value.trim()
  if (!q || sending.value) return

  messages.value.push({role:'user',content:q})
  messages.value.push({role:'assistant',content:'',streaming:true})
  const ans:Msg = messages.value[messages.value.length - 1] as Msg
  input.value = ''
  sending.value = true
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
    onCitations:(cs)=>{ ans.citations = cs },
    onMeta:(meta)=>{
      if (meta.messageId != null) ans.id = meta.messageId
      if (meta.traceId) ans.traceId = meta.traceId
    },
    onError:()=>{ flushPending(); ans.streaming=false; ans.ts=Date.now(); ans.content+='\n\n> **生成失败**'; sending.value=false },
    onDone:()=>{ flushPending(); ans.streaming=false; ans.ts=Date.now(); sending.value=false; scrollToBottomIfStuck() },
  }, agent.value)
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
        <div class="empty-illustration"><RobotOutlined /></div>
        <h4 class="empty-title">开始对话</h4>
        <p class="empty-desc">在知识库入库文档后，即可基于文档提问</p>
        <div class="empty-suggestions">
          <a-tag
            v-for="s in ['总结这篇文档','文档中提到了哪些关键信息？','帮我提取核心要点']"
            :key="s" class="suggestion-tag" @click="input=s; send()"
          >{{ s }}</a-tag>
        </div>
      </div>

      <div v-for="(m,i) in messages" :key="i" class="msg" :class="m.role">
        <div v-if="m.role==='assistant'" class="avatar avatar-ai"><RobotOutlined /></div>
        <div class="bubble">
          <div v-if="m.streaming&&!m.content" class="typing-indicator"><span></span><span></span><span></span></div>
          <div v-else-if="m.role==='user'" class="msg-text user-text">{{ m.content }}</div>
          <div v-else class="markdown-body" v-html="renderWithCitations(m)" @click="onBubbleClick($event, m)"></div>
          <span v-if="m.streaming&&m.content" class="stream-cursor">▍</span>
          <!-- 操作条：轨迹回看 + 引用溯源 + traceId（完整展示可复制）+ OpenObserve 全链路（流式结束后） -->
          <div v-if="m.role==='assistant' && !m.streaming && (m.id || m.trace)" class="msg-actions">
            <button class="action-btn" @click="openTrace(m)">
              <ApartmentOutlined />轨迹
            </button>
            <button v-if="usedCitations(m).length" class="action-btn" @click="openCitations(m)">
              <FileSearchOutlined />引用 {{ usedCitations(m).length }}
            </button>
            <template v-if="m.traceId">
              <span class="trace-id-full" :title="'traceId: ' + m.traceId">{{ m.traceId }}</span>
              <button class="action-btn" title="复制 traceId" @click="copyWithToast(m.traceId)">
                <CopyOutlined />
              </button>
              <button class="action-btn" title="查看 OpenObserve 完整链路" @click="openObsLink(m)">
                <DeploymentUnitOutlined />链路
              </button>
            </template>
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

    <!-- 输入区 -->
    <div class="composer">
      <div class="composer-bar">
        <span class="bar-label">Agent 范式</span>
        <a-select v-model:value="agent" size="small" class="bar-select">
          <a-select-option v-for="p in PARADIGMS" :key="p.value" :value="p.value">
            {{ p.label }} · {{ p.desc }}
          </a-select-option>
        </a-select>
      </div>
      <div class="composer-inner">
        <a-input ref="inputRef" v-model:value="input" placeholder="输入问题…" :disabled="sending"
          size="large" variant="filled" class="composer-input" @press-enter="send" />
        <a-button type="primary" :loading="sending" class="composer-btn" @click="send">
          <template #icon><SendOutlined /></template>
        </a-button>
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

.scroll-to-bottom { position:absolute; right:24px; bottom:84px; width:36px; height:36px; border-radius:50%; background:var(--color-surface); border:1px solid var(--color-border); box-shadow:0 2px 10px rgba(0,0,0,.12); display:flex; align-items:center; justify-content:center; cursor:pointer; color:var(--color-primary); z-index:50; transition:background .15s; }
.scroll-to-bottom:hover { background:var(--color-surface-secondary); }
.fade-enter-active, .fade-leave-active { transition:opacity .2s; }
.fade-enter-from, .fade-leave-to { opacity:0; }
.log { flex:1; overflow-y:auto; padding:20px 24px; display:flex; flex-direction:column; gap:16px; }
.log-bottom { height:4px; flex-shrink:0; }

.load-earlier { display:flex; justify-content:center; flex-shrink:0; }
.empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; padding:40px 20px; }
.empty-illustration { width:56px; height:56px; display:flex; align-items:center; justify-content:center; background:var(--color-primary-light); border:1px solid var(--color-border-light); border-radius:var(--radius-lg); font-size:24px; color:var(--color-primary); margin-bottom:12px; }
.empty-title { margin:0 0 6px; font-size:16px; font-weight:600; }
.empty-desc { margin:0 0 16px; font-size:13px; color:var(--color-ink-secondary); }
.empty-suggestions { display:flex; flex-wrap:wrap; gap:6px; justify-content:center; }
.suggestion-tag { cursor:pointer; }

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
.action-btn { display:inline-flex; align-items:center; gap:4px; border:none; background:none; padding:2px 8px; font-size:12px; color:var(--color-ink-tertiary); cursor:pointer; border-radius:var(--radius-sm); transition:color .15s, background .15s; }
.action-btn:hover { color:var(--color-primary); background:var(--color-primary-light); }
/* traceId 完整展示：等宽小字，可选中整段，允许换行 */
.trace-id-full {
  font-family: var(--font-display);
  font-size: 11px;
  line-height: 1.5;
  color: var(--color-ink-tertiary);
  word-break: break-all;
  user-select: all;
  -webkit-user-select: all;
  max-width: 100%;
}

.markdown-body { font-family:var(--font-body); font-size:14px; line-height:1.7; color:var(--color-ink); background:transparent; }
.markdown-body :deep(pre){ background:var(--color-surface-secondary)!important; border-radius:var(--radius-md); padding:10px 14px!important; overflow-x:auto; font-size:13px; border:1px solid var(--color-border-light); }
.markdown-body :deep(code:not(pre code)){ font-family:var(--font-display); font-size:13px; background:var(--color-surface-secondary); padding:1px 4px; border-radius:3px; }
.markdown-body :deep(pre code){ background:transparent!important; padding:0!important; }
.markdown-body :deep(p){ margin:0 0 6px; }
.markdown-body :deep(table){ border-collapse:collapse; margin:6px 0; width:100%; font-size:13px; }
.markdown-body :deep(th),.markdown-body :deep(td){ border:1px solid var(--color-border); padding:5px 8px; text-align:left; }
.markdown-body :deep(th){ background:var(--color-surface-secondary); font-weight:600; }
.markdown-body :deep(a){ color:var(--color-primary); }

.composer { padding:12px 20px; border-top:1px solid var(--color-border); background:var(--color-surface); flex-shrink:0; }
.composer-bar { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
.bar-label { font-family:var(--font-display); font-size:11px; letter-spacing:0.05em; text-transform:uppercase; color:var(--color-ink-tertiary); }
.bar-select { width:210px; }
.composer-inner { display:flex; gap:8px; align-items:center; }
.composer-input { flex:1; }
.composer-btn { width:38px; height:38px; flex-shrink:0; }

@media (max-width: 768px) {
  .log { padding:14px 12px; gap:12px; }
  .msg { max-width:92%; }
  .composer { padding:8px 10px; padding-bottom: calc(8px + env(safe-area-inset-bottom)); }
}
</style>
