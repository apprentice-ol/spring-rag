<script setup lang="ts">
import { ref, nextTick, watch, computed } from 'vue'
import {
  SendOutlined, UserOutlined, RobotOutlined,
  VerticalAlignBottomOutlined, MenuUnfoldOutlined, MenuOutlined,
} from '@ant-design/icons-vue'
import { streamChat } from '../api/chat'
import { PARADIGMS } from './evalShared'
import { messages, activeId, currentTitle, type Msg } from '../composables/useChatState'
import { useIsMobile } from '../composables/useSplitter'
import ConversationList from './ConversationList.vue'
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
    onError:()=>{ flushPending(); ans.streaming=false; ans.content+='\n\n> **生成失败**'; sending.value=false },
    onDone:()=>{ flushPending(); ans.streaming=false; sending.value=false; scrollToBottomIfStuck() },
  }, agent.value)
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
          <div v-else class="markdown-body" v-html="renderMarkdown(m.content)"></div>
          <span v-if="m.streaming&&m.content" class="stream-cursor">▍</span>
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

.empty { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; padding:40px 20px; }
.empty-illustration { width:56px; height:56px; display:flex; align-items:center; justify-content:center; background:linear-gradient(135deg,rgba(15,118,110,.08),rgba(20,184,166,.08)); border-radius:50%; font-size:24px; color:var(--color-primary); margin-bottom:12px; }
.empty-title { margin:0 0 6px; font-size:16px; font-weight:600; }
.empty-desc { margin:0 0 16px; font-size:13px; color:var(--color-ink-secondary); }
.empty-suggestions { display:flex; flex-wrap:wrap; gap:6px; justify-content:center; }
.suggestion-tag { cursor:pointer; }

.msg { display:flex; gap:10px; align-items:flex-start; max-width:85%; }
.msg.user { align-self:flex-end; flex-direction:row-reverse; }
.avatar { width:28px; height:28px; border-radius:50%; display:flex; align-items:center; justify-content:center; font-size:13px; flex-shrink:0; }
.avatar-ai { background:linear-gradient(135deg,var(--color-primary),#14b8a6); color:#fff; }
.avatar-user { background:#f0f0ed; color:var(--color-ink-secondary); }
.bubble { padding:10px 14px; border-radius:12px; font-size:14px; line-height:1.6; min-width:36px; }
.msg.assistant .bubble { background:var(--color-surface); border:1px solid var(--color-border); border-top-left-radius:4px; }
.msg.user .bubble { background:linear-gradient(135deg,var(--color-primary),#0d9488); color:#fff; border-top-right-radius:4px; }
.user-text { white-space:pre-wrap; word-break:break-word; }
.stream-cursor { color:var(--color-signal); animation:blink .9s step-end infinite; font-weight:bold; }
@keyframes blink { 50%{opacity:0} }
.typing-indicator { display:flex; gap:4px; padding:4px 0; }
.typing-indicator span { width:6px; height:6px; border-radius:50%; background:var(--color-ink-tertiary); animation:typing-bounce 1.4s ease-in-out infinite; }
.typing-indicator span:nth-child(2){animation-delay:.2s}
.typing-indicator span:nth-child(3){animation-delay:.4s}
@keyframes typing-bounce { 0%,60%,100%{transform:translateY(0);opacity:.4} 30%{transform:translateY(-5px);opacity:1} }

.markdown-body { font-family:var(--font-body); font-size:14px; line-height:1.7; color:var(--color-ink); background:transparent; }
.markdown-body :deep(pre){ background:#f5f5f0!important; border-radius:6px; padding:10px 14px!important; overflow-x:auto; font-size:13px; border:1px solid #e2e3dd; }
.markdown-body :deep(code:not(pre code)){ font-family:var(--font-display); font-size:13px; background:#f0f0ed; padding:1px 4px; border-radius:3px; }
.markdown-body :deep(pre code){ background:transparent!important; padding:0!important; }
.markdown-body :deep(p){ margin:0 0 6px; }
.markdown-body :deep(table){ border-collapse:collapse; margin:6px 0; width:100%; font-size:13px; }
.markdown-body :deep(th),.markdown-body :deep(td){ border:1px solid var(--color-border); padding:5px 8px; text-align:left; }
.markdown-body :deep(th){ background:var(--color-surface-secondary); font-weight:600; }
.markdown-body :deep(a){ color:var(--color-primary); }

.composer { padding:12px 20px; border-top:1px solid var(--color-border); background:var(--color-surface); flex-shrink:0; }
.composer-bar { display:flex; align-items:center; gap:8px; margin-bottom:8px; }
.bar-label { font-size:12px; color:var(--color-ink-tertiary); }
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
