<script setup lang="ts">
import { ref, nextTick, watch, computed, onMounted, onUnmounted } from 'vue'
import {
  SendOutlined, UserOutlined, RobotOutlined,
  VerticalAlignBottomOutlined, MenuUnfoldOutlined, MenuOutlined,
  ApartmentOutlined, DeploymentUnitOutlined, CopyOutlined, FileSearchOutlined,
} from '@ant-design/icons-vue'
import { streamChat, cancelChat, type AgentTrace, type Citation, type ClarifyChoice, type ClarifyEvent, type ClarifySlotQuestion } from '../api/chat'
import { getAgentTraceByMessage, toAgentTrace } from '../api/agentTrace'
import { traceDetailUrl } from '../api/eval'
import { copyWithToast } from '../composables/useClipboard'
import { activeParadigms, chatParadigmOptions } from './evalShared'
import { messages, activeId, currentTitle, hasEarlierMessages, loadEarlierMessages, type Msg } from '../composables/useChatState'
import { useIsMobile } from '../composables/useSplitter'
import ConversationList from './ConversationList.vue'
import AgentTraceTree from './AgentTraceTree.vue'
import ParadigmHelp from './ParadigmHelp.vue'
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

/** 诊断结论里「上下文来源」段的标题行（服务端 ConcludeExecutor 固定追加，不进正文渲染）。 */
const CONTEXT_TITLE = '本次诊断的上下文自动补全'

/**
 * 「上下文来源」段在正文里的起点；没有该段返回 -1。
 *
 * <p>服务端追加形态为 {@code \n\n——\n本次诊断的上下文自动补全：\n- slot = value（依据）}。
 * 从标题往回找最近的一条「——」：结论正文自己也可能出现「——」，倒着找必然命中服务端那条。</p>
 */
function contextBlockStart(text:string): number {
  const title = text.indexOf(CONTEXT_TITLE)
  if (title < 0) return -1
  const marker = text.lastIndexOf('——', title)
  return marker < 0 ? title : marker
}

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
  const content = m.content ?? ''
  const start = contextBlockStart(content)
  // 上下文来源段由 contextRows 单独渲染成结构化清单，正文里剥掉——否则同一段会显示两遍
  const html = renderMarkdown(start < 0 ? content : content.slice(0, start))
  if (!m.citations?.length) return html
  const refs = new Set(m.citations.map(c => c.ref))
  // ① 链接式：<a href="#cite-N">N</a> → 角标
  const linked = html.replace(/<a href="#cite-(\d{1,2})">\1<\/a>/g, (whole, num: string) => {
    return refs.has(Number(num)) ? makeBadge(Number(num)) : whole
  })
  // ② 裸 [N]（sup 标签内无方括号，二次替换不会误伤）
  const badged = linked.replace(/\[(\d{1,2})\]/g, (whole, num: string) => {
    return refs.has(Number(num)) ? makeBadge(Number(num)) : whole
  })
  return withCodeCopy(badged)
}

/**
 * 给每个代码块加一条「复制」操作条（结论里的修复报文最常用）。
 * 原始代码存进 data-code（base64，避免 HTML 属性转义问题），点击时由 onBubbleClick 取用。
 */
function withCodeCopy(html: string): string {
  return html.replace(/<pre class="hljs"><code>([\s\S]*?)<\/code><\/pre>/g, (whole, inner: string) => {
    const text = inner.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&')
    let encoded = ''
    try { encoded = btoa(unescape(encodeURIComponent(text))) } catch { encoded = '' }
    return `<div class="code-block"><div class="code-bar"><button class="code-copy" data-code="${encoded}">复制</button></div>${whole}</div>`
  })
}

/**
 * 结论正文里的「上下文来源」段 → 结构化行。
 *
 * <p>服务端（ConcludeExecutor）用固定格式追加：{@code ——
本次诊断的上下文自动补全：
- slot = value（依据）}，
 * 这里把它从正文里摘出来单独渲染成来源清单，正文只留结论本身。</p>
 */
function contextRows(m: Msg): { slot: string; value: string; evidence: string }[] {
  if (m.role !== 'assistant') return []
  const content = m.content ?? ''
  const start = contextBlockStart(content)
  if (start < 0) return []
  const block = content.slice(start)
  const rows: { slot: string; value: string; evidence: string }[] = []
  for (const line of block.split('\n')) {
    const hit = line.match(/^-\s*([a-z_]+)\s*=\s*(.*?)（(.*)）\s*$/)
    if (hit) rows.push({ slot: slotLabel(hit[1]), value: shortPlain(hit[2]), evidence: hit[3] })
  }
  return rows
}

/** 来源行里的值：截断展示（完整值在 title） */
function shortPlain(value: string): string {
  const t = (value || '').trim()
  return t.length > 48 ? t.slice(0, 48) + '…' : t
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
  const copyBtn = (e.target as HTMLElement).closest('button.code-copy')
  if (copyBtn) {
    const encoded = copyBtn.getAttribute('data-code') || ''
    try {
      void copyWithToast(decodeURIComponent(escape(atob(encoded))), '已复制代码块')
    } catch { /* 编码异常忽略 */ }
    return
  }
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
 * 其余非当前范式的旧值（naive/react/crag…）也回落到自动档——旧值→新范式的解析
 * 由后端 AgentCatalog.ALIASES 负责，前端不再维护那份映射。回落而非原样保留，
 * 是为了避免选择器里选中的值不在选项列表中（显示空白）。
 */
const agent = ref(migrateAgentChoice(localStorage.getItem('rag_chat_agent')))
watch(agent, (v) => {
  if (v) localStorage.setItem('rag_chat_agent', v)
  else localStorage.removeItem('rag_chat_agent')
})

function migrateAgentChoice(v: string | null): string {
  if (!v || v === 'knowledge') return '' // 自动档（历史默认值，非显式选择）
  return activeParadigms().some((p) => p.value === v) ? v : ''
}
const logRef = ref<HTMLElement>()
const inputRef = ref<{ focus: () => void } | null>(null)
/** composer 聚焦态（卡片描边 + 聚焦环） */
const composerFocused = ref(false)
/** 移动端：左侧会话列表抽屉开关 */
const mobileConvOpen = ref(false)

/**
 * 会话自主档位（人在环中 P3）：L1 多问我 / L2 默认 / L3 少问我。空值 = 跟随会话记录（缺省 L2）。
 * 随每次请求发送（后端按「本轮参数 > 会话记录 > 缺省」定档），也随挂起会话落库延续。
 */
const autonomy = ref('')
const AUTONOMY_OPTIONS = [
  { value: '', label: '默认档', desc: '目录缺省 + 高置信推断 + 日志反查（推荐）' },
  { value: 'L1', label: '多问我', desc: '不自动补全：缺什么问什么，推断与日志反查全关' },
  { value: 'L3', label: '少问我', desc: '推断门槛放宽、日志多查一轮，尽量减少追问' },
]

/** 槽位短名（已有目录七槽；未知槽位名原样显示） */
const SLOT_LABELS: Record<string, string> = {
  environment: '环境', interface: '接口', time: '时间',
  error: '报错', payload: '报文', symptoms: '现象', trace_id: '关键数据',
}
function slotLabel(slot: string): string { return SLOT_LABELS[slot] || slot }

/** 自动补全来源 → 角标文案（与后端 SlotProvenance.label 同口径） */
const PROVENANCE_LABELS: Record<string, string> = {
  llm: '模型推断', log_query: '日志反查', default: '缺省值', rule: '规则提取',
  user_override: '已更正', user: '你提供',
}
function provenanceLabel(source?: string | null): string {
  return PROVENANCE_LABELS[source || ''] || '自动补全'
}

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
  await sendText(input.value.trim())
  input.value = ''
}

/**
 * 决策移交点选（人在环中）：把选项 value 以 #decision: 前缀发出（后端 HumanResponse.parse 消费），
 * 用户气泡显示选项 label 而非协议原文；点过的卡片立即退场（决策已交，防止重复点击）。
 */
async function sendDecision(value: string, label: string) {
  if (sending.value) return
  const card = decisionTarget.value
  if (card) card.clarify = undefined
  decisionTarget.value = null
  await sendText(`#decision:${value}`, label)
}

/**
 * 决策卡片点选分流：终止类选项 = 立即回传（确定性协议，零模型调用）；
 * 「我补充信息，继续排查」这类 = **聚焦输入框让用户说**——空发一条会被后端判为"没收到新信息"
 * 而原样再问一轮，按钮看起来就像坏的。
 */
function decideAction(c: ClarifyChoice) {
  if (sending.value) return
  if (c.value === 'terminate') {
    void sendDecision(c.value, c.label)
    return
  }
  nextTick(() => inputRef.value?.focus())
}

/**
 * 选项按钮分流（按卡片类别）：
 * DECIDE 的「继续」= 让用户说（聚焦输入框）；DECIDE 的「终止」与 CONFIRM 的「确认」= 立即确定性回传。
 */
function onOptionClick(kind: string | null | undefined, c: ClarifyChoice) {
  if (sending.value) return
  if (c.value === 'terminate' || kind === 'CONFIRM') {
    void sendDecision(c.value, c.label)
    return
  }
  nextTick(() => inputRef.value?.focus())
}

/** 当前待决策卡片（点选后置空退场）；DECIDE 才有，问齐卡片不进 */
const decisionTarget = ref<Msg | null>(null)

/**
 * 点选纠正自动补全值（人在环中 P3）：回传 #override:<slot>=<value>（后端 HumanResponse.parse
 * 确定性消费，零模型调用），用户气泡显示「环境 改为 test」而非协议原文。
 */
/** 就地编辑态（一次只编辑一行）：槽位名 + 编辑中的值 */
const editingSlot = ref('')
const editingValue = ref('')

function startEdit(i: number, r: ClarifySlotQuestion) {
  editingSlot.value = r.slot
  editingValue.value = pickOf(i, r.slot) || r.value || ''
}

function cancelEdit() {
  editingSlot.value = ''
  editingValue.value = ''
}

/** 就地编辑确定 → 进「待提交」集合（不直接发送，用户可继续改别的再一起提交） */
function commitEdit(i: number, r: ClarifySlotQuestion) {
  const value = editingValue.value.trim()
  cancelEdit()
  setPick(i, r.slot, value)
}

/**
 * 卡片内「待提交」的选择：key = `${消息下标}|${槽位}`，value = 选中值（空串 = 清空该项）。
 *
 * <p>点选只改这张卡的本地状态，**不倒进输入框、也不每点一条消息**——想改几项就改几项，
 * 最后点「提交」一次性发出去（`#fill:<json>`，后端确定性消费、零模型调用）。</p>
 */
const picks = ref<Record<string, string>>({})
const pickKey = (i: number, slot: string) => `${i}|${slot}`

function pickOf(i: number, slot: string): string {
  return picks.value[pickKey(i, slot)] ?? ''
}

function hasPick(i: number, slot: string): boolean {
  return pickKey(i, slot) in picks.value
}

function pickCount(i: number): number {
  const prefix = i + '|'
  return Object.keys(picks.value).filter(k => k.startsWith(prefix)).length
}

function setPick(i: number, slot: string, value: string) {
  if (sending.value) return
  picks.value = { ...picks.value, [pickKey(i, slot)]: value }
}

function clearPick(i: number, slot: string) {
  const next = { ...picks.value }
  delete next[pickKey(i, slot)]
  picks.value = next
}

function clearPicks(i: number) {
  const prefix = i + '|'
  const next: Record<string, string> = {}
  for (const [k, v] of Object.entries(picks.value)) {
    if (!k.startsWith(prefix)) next[k] = v
  }
  picks.value = next
}

/** 提交这张卡上的全部选择：一个 `#fill:<json>`（多槽一次到位，后端按当前值自动判补缺/推翻） */
async function submitPicks(i: number) {
  if (sending.value) return
  const prefix = i + '|'
  const entries = Object.entries(picks.value).filter(([k]) => k.startsWith(prefix))
  if (!entries.length) return
  const payload: Record<string, string> = {}
  const labels: string[] = []
  for (const [key, value] of entries) {
    const slot = key.slice(prefix.length)
    payload[slot] = value
    labels.push(`${slotLabel(slot)} ${value || '清空'}`)
  }
  clearPicks(i)
  await sendText(`#fill:${JSON.stringify(payload)}`, labels.join(' · '))
}

/**
 * 问齐卡片的引导句：环内 ask_user 由模型组织问法（带上下文），入环问齐则是套话——
 * 套话不重复展示（eyebrow 已经说了是补充信息），短问句也不占地方。
 */
function leadOf(clarify: ClarifyEvent): string {
  const s = (clarify.summary || '').trim()
  return s.length > 24 && !s.startsWith('请补充以下信息') ? s : ''
}

/** 假设的验证状态徽标文案（未知状态原样回显——后端加新状态不至于渲染空白）。 */
function hypStatusLabel(status: string): string {
  const s = (status || '').toLowerCase()
  if (s === 'verified') return '已证实'
  if (s === 'disproved') return '已排除'
  if (s === 'unverified') return '待验证'
  return status || '待验证'
}

/** 卡片上的值展示：ISO 时间窗压成人读形态，长值截断（完整值挂 title） */
function shortValue(value?: string | null): string {
  const v = (value || '').trim()
  const iso = v.match(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})~(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})$/)
  if (iso) {
    const sameDay = iso[1] === iso[3]
    return sameDay ? `${iso[1]} ${iso[2]} ~ ${iso[4]}` : `${iso[1]} ${iso[2]} ~ ${iso[3]} ${iso[4]}`
  }
  return v.length > 56 ? v.slice(0, 56) + '…' : v
}

/** 卡片上的「已自动补全」行（带值即为机器补全项；旧事件无该字段则为空数组） */
function overridableRows(m: Msg): ClarifySlotQuestion[] {
  return (m.clarify?.reviewed || []).filter(r => !!r.value)
}

/** 值来自用户自己（user_override）：只展示，不给「改/清空」——那是他刚说的话，再问一遍「要改吗」是噪音。
 *  后端曾把它整个排除在结构化投影外，结果卡片拿不到 reviewed、掉进纯文本兜底分支糊成开发口径 */
function isUserProvided(r: ClarifySlotQuestion): boolean {
  return r.provenance === 'user_override'
}

/** 真正可改的行数——提示语要不要写「不对可点「改」或「清空」」由它决定：写了却没按钮就是骗人 */
function editableRowCount(m: Msg): number {
  return overridableRows(m).filter(r => !isUserProvided(r)).length
}

/** 「我理解的信息」块标题：全是用户自己给的值时不能说「已自动补全」（根本没自动补过什么） */
function autoBlockTitle(m: Msg): string {
  const rows = overridableRows(m)
  const editable = editableRowCount(m)
  const head = m.clarify?.kind === 'CONFIRM' || editable === 0 ? '我理解的信息' : '已自动补全'
  return editable > 0 ? `${head} · 不对可点「改」或「清空」` : head
}

/** 旧事件兜底行「slot = value（依据）」拆成三段，好在卡片上按用户口径渲染；拆不开就整行透出 */
function legacyRows(m: Msg): Array<{ label: string; value: string; evidence: string }> {
  return (m.clarify?.evidence || []).map(line => {
    // value 用贪婪匹配、依据取最后一个括号组：value 自己带全角括号时不会被截断
    const parsed = line.match(/^(\S+)\s*=\s*(.*)（([^（）]*)）\s*$/)
    if (!parsed) return { label: '', value: line, evidence: '' }
    return { label: slotLabel(parsed[1]), value: parsed[2], evidence: parsed[3] }
  })
}

/** 挂起卡片（问齐/确认）已完整表达该消息时，不再重复渲染正文文本版。
 *  实时路径有守卫（onContent 里 `if (ans.clarify) return`），但刷新/切会话走历史渲染没有，
 *  同一句「已自动补全：…」会被正文和卡片各说一遍。DECIDE 不在此列——它的正文另有内容 */
function cardHidesBody(m: Msg): boolean {
  const kind = m.clarify?.kind
  if (kind !== 'CLARIFY' && kind !== 'CONFIRM') return false
  return (m.clarify?.questions?.length || 0) > 0 || overridableRows(m).length > 0
}

/** 纠正候选：目录候选值去掉当前值（当前值点它没意义），最多展示 4 个防卡片膨胀 */
function correctOptions(r: ClarifySlotQuestion): string[] {
  return (r.options || []).filter(o => o !== r.value).slice(0, 4)
}

async function sendText(q: string, display?: string) {
  if (!q || sending.value) return

  messages.value.push({role:'user',content:display || q})
  messages.value.push({role:'assistant',content:'',streaming:true})
  const ans:Msg = messages.value[messages.value.length - 1] as Msg
  sending.value = true
  stopping.value = false
  abortCtl = new AbortController()
  forceScrollToBottom()

  let pending = ''
  let contentRaf = 0
  function flushPending(){ if(pending){ ans.content+=pending; pending='' }; contentRaf=0 }

  await streamChat(q, activeId.value, {
    onContent:(chunk)=>{
      // 挂起卡片已接管展示（问齐/决策移交）：后端随后补发的正文文本版（askText）不进气泡，防双发
      if (ans.clarify) return
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
    onClarify:(c)=>{
      ans.clarify = c
      // 卡片接管展示：清掉此前累积的过程文案（"正在排查，请稍候…"等），后续正文由 onContent 丢弃
      pending = ''
      ans.content = ''
      // DECIDE 决策移交卡片登记为待决策目标（点选后退场）
      decisionTarget.value = c.kind === 'DECIDE' ? ans : null
    },
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
  }, agent.value, abortCtl.signal, autonomy.value)
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
/** 轨迹配套上下文：本轮提问（首步输入口径）与回答引用（命中表「被引用」标注） */
const drawerQuestion = ref<string | undefined>(undefined)
const drawerCitations = ref<{ ref: number }[] | undefined>(undefined)
/* ============ 轨迹抽屉宽度（可拖拽，偏好落 localStorage） ============
   一屏装得下多少轨迹，取决于读者此刻在看什么：扫一眼步骤名 720 够，逐条比对
   命中表的分数与通道就嫌挤。把宽度交给他自己拖，比替他猜一个固定值更靠谱。

   上限跟着窗口走而不是取常量：一条 1200px 的抽屉在 1280 宽的屏上会把正文整个盖住，
   而抽屉的用途恰恰是「对着回答看轨迹」——留不下一列正文就没意义了。 */
const TRACE_W_KEY = 'rag-trace-w'
/** 默认比 agent-framework 的 720 宽一档：那边命中表 6 列，这边多一列「引用」 */
const TRACE_W_DEFAULT = 800
const TRACE_W_MIN = 480
const TRACE_W_MAX = 1200

function clampTraceWidth(value: number): number {
  const viewport = typeof window === 'undefined' ? 800 : window.innerWidth
  const max = Math.max(TRACE_W_MIN, Math.min(TRACE_W_MAX, Math.round(viewport * 0.92)))
  if (!Number.isFinite(value)) {
    return Math.min(TRACE_W_DEFAULT, max)
  }
  return Math.min(max, Math.max(TRACE_W_MIN, Math.round(value)))
}

/** 存储里的值可能是在更大的屏上写下的，读回来按当前窗口再钳一次。 */
function readStoredTraceWidth(): number {
  try {
    const raw = window.localStorage.getItem(TRACE_W_KEY)
    return raw === null ? TRACE_W_DEFAULT : Number(raw)
  } catch {
    return TRACE_W_DEFAULT // 隐私模式下 localStorage 不可用
  }
}

const traceDrawerWidth = ref(clampTraceWidth(readStoredTraceWidth()))

function persistTraceWidth() {
  try {
    window.localStorage.setItem(TRACE_W_KEY, String(traceDrawerWidth.value))
  } catch {
    /* 写入失败只影响下次打开的默认宽度，不值得打扰用户 */
  }
}

/** 拖动中只改内存值，松手才落盘——每帧写一次 localStorage 是白费。 */
function startTraceResize(event: PointerEvent) {
  event.preventDefault()
  const onMove = (move: PointerEvent) => {
    // 抽屉贴右边，所以「指针到窗口右缘的距离」才是它该有的宽度
    traceDrawerWidth.value = clampTraceWidth(window.innerWidth - move.clientX)
  }
  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    persistTraceWidth()
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function resetTraceWidth() {
  traceDrawerWidth.value = clampTraceWidth(TRACE_W_DEFAULT)
  persistTraceWidth()
}

/** 键盘微调：手柄可聚焦，方向键各挪 40px。 */
function nudgeTraceResize(step: number) {
  traceDrawerWidth.value = clampTraceWidth(traceDrawerWidth.value + step)
  persistTraceWidth()
}

/** 窗口变小后原宽度可能盖住整个正文，跟着收一收。 */
function onViewportResize() {
  const clamped = clampTraceWidth(traceDrawerWidth.value)
  if (clamped !== traceDrawerWidth.value) {
    traceDrawerWidth.value = clamped
  }
}
onMounted(() => window.addEventListener('resize', onViewportResize))
onUnmounted(() => window.removeEventListener('resize', onViewportResize))

async function openTrace(m: Msg) {
  traceDrawerOpen.value = true
  // 该回答前最近一条 user 消息即本轮提问；引用来自消息自身（与正文 [N] 角标同源）
  const idx = messages.value.indexOf(m)
  drawerQuestion.value = idx > 0
    ? [...messages.value].slice(0, idx).reverse().find((x) => x.role === 'user')?.content
    : undefined
  drawerCitations.value = m.citations
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
          <!-- 有问齐/确认卡片时不渲染正文文本版（历史渲染没有实时路径那个「卡片已接管」守卫，会双发） -->
          <div v-else-if="!cardHidesBody(m)" class="markdown-body" v-html="renderWithCitations(m)" @click="onBubbleClick($event, m)"></div>
          <!-- 诊断结论的「上下文来源」段：服务端用固定格式追加（—— / 本次诊断的上下文自动补全：/ - slot = value（依据）），
               解析成结构化行渲染，不再当普通正文 -->
          <div v-if="m.role==='assistant' && contextRows(m).length" class="conclusion-context">
            <div class="conclusion-context-title">本次诊断的上下文来源</div>
            <div v-for="(row,ri) in contextRows(m)" :key="ri" class="conclusion-context-row">
              <span class="auto-slot">{{ row.slot }}</span>
              <span class="auto-value" :title="row.value">{{ row.value }}</span>
              <span class="conclusion-context-evidence">{{ row.evidence }}</span>
            </div>
          </div>
          <!-- 运维诊断问齐卡片（clarify 事件）：缺什么问什么 + 已补全的机器值可纠正。
               候选值点选=填入输入框（不直接发送）——一轮答全多项，而不是每点一次等一整轮排查 -->
          <div v-if="m.clarify && m.clarify.kind !== 'DECIDE'" class="clarify-card"
               :class="{ stale: i < messages.length - 1 }">
            <div class="hitl-eyebrow">{{ m.clarify.kind === 'CONFIRM' ? '确认信息' : '补充信息' }}</div>
            <!-- 模型自己组织的问法（环内 ask_user）：比逐槽问句多一层上下文，非套话才显示 -->
            <div v-if="leadOf(m.clarify)" class="clarify-lead">{{ leadOf(m.clarify) }}</div>
            <div v-for="(q,qi) in m.clarify.questions" :key="qi" class="clarify-item">
              <span class="clarify-q"><span class="num clarify-no">{{ qi+1 }}</span>{{ q.question }}</span>
              <span v-if="q.hint && !(q.options && q.options.length)" class="clarify-hint">{{ q.hint }}</span>
              <div v-if="q.options && q.options.length" class="clarify-options">
                <button v-for="opt in q.options" :key="opt" class="clarify-opt"
                        :class="{ picked: pickOf(i, q.slot) === opt }"
                        :disabled="sending" @click="setPick(i, q.slot, opt)">{{ opt }}</button>
              </div>
            </div>
            <!-- 我理解的信息（P3）：值 + 来源角标 + 依据（回答"这值哪来的"）+ 就地改/清空。
                 两条诚实性约束：① 用户自己给的值不给改动入口——那是他刚说的话，再问「要改吗」是噪音；
                 ② 没有可改行时标题不承诺「改成 …」按钮（提示语写了却没按钮就是骗人，曾实测踩到） -->
            <div v-if="overridableRows(m).length" class="clarify-auto">
              <div class="clarify-auto-title">{{ autoBlockTitle(m) }}</div>
              <div v-for="r in overridableRows(m)" :key="r.slot" class="auto-row">
                <template v-if="editingSlot !== r.slot">
                  <span class="auto-slot">{{ slotLabel(r.slot) }}</span>
                  <!-- 用户自己刚给的值：只读展示（不进 pickCount，也没有编辑入口） -->
                  <template v-if="isUserProvided(r)">
                    <span class="auto-value readonly" :title="r.value || ''">{{ shortValue(r.value) }}</span>
                    <span class="auto-badge auto-badge-user_override">{{ provenanceLabel(r.provenance) }}</span>
                  </template>
                  <!-- 值本身即入口：hover 变主色 + 虚线下划线，点一下进就地编辑 -->
                  <template v-else-if="hasPick(i, r.slot)">
                    <span class="auto-value picked" :title="'待提交：' + (pickOf(i, r.slot) || '（清空）')">
                      → {{ pickOf(i, r.slot) ? shortValue(pickOf(i, r.slot)) : '清空' }}
                    </span>
                    <button class="clarify-opt auto-act" title="撤销这次修改"
                            @click="clearPick(i, r.slot)">撤销</button>
                  </template>
                  <template v-else>
                    <span class="auto-value" :title="(r.value || '') + '（点击修改）'"
                          @click="startEdit(i, r)">{{ shortValue(r.value) }}</span>
                    <span class="auto-badge" :class="'auto-badge-' + (r.provenance || 'auto')">{{ provenanceLabel(r.provenance) }}</span>
                    <span class="auto-fix">
                      <button v-for="opt in correctOptions(r)" :key="opt" class="clarify-opt auto-opt"
                              :class="{ picked: pickOf(i, r.slot) === opt }"
                              :disabled="sending" @click="setPick(i, r.slot, opt)">改成 {{ opt }}</button>
                      <button class="clarify-opt auto-act" :disabled="sending" title="手动填一个新值"
                              @click="startEdit(i, r)">改</button>
                      <button class="clarify-opt auto-act auto-act-clear" :disabled="sending"
                              title="清掉这项（当作没填）" @click="setPick(i, r.slot, '')">清空</button>
                    </span>
                  </template>
                  <span v-if="r.evidence" class="auto-evidence" :title="r.evidence">{{ r.evidence }}</span>
                </template>
                <template v-else>
                  <span class="auto-slot">{{ slotLabel(r.slot) }}</span>
                  <input v-model="editingValue" class="auto-input" :placeholder="r.value || '新值…'"
                         @keydown.enter="commitEdit(i, r)" @keydown.esc="cancelEdit()" />
                  <span class="auto-fix">
                    <button class="clarify-opt" :disabled="sending" @click="commitEdit(i, r)">确定</button>
                    <button class="clarify-opt" @click="cancelEdit()">取消</button>
                  </span>
                </template>
              </div>
            </div>
            <!-- 旧事件兼容：没有结构化 reviewed 时按文本透出 autoNote。
                 标题不写「不对就点「改成 …」」——这条分支根本没有按钮；行内 `slot = value（依据）`
                 拆成三段按用户口径渲染（拆不开才整行透出），老消息也不至于糊一脸开发字段 -->
            <div v-else-if="m.clarify.evidence && m.clarify.evidence.length" class="clarify-auto">
              <div class="clarify-auto-title">我理解的信息</div>
              <div v-for="(row,ei) in legacyRows(m)" :key="ei" class="auto-row">
                <span v-if="row.label" class="auto-slot">{{ row.label }}</span>
                <span class="auto-value readonly" :title="row.value">{{ row.value }}</span>
                <span v-if="row.evidence" class="auto-evidence">{{ row.evidence }}</span>
              </div>
            </div>
            <!-- CONFIRM 确认门：改完点「确认，开始排查」才进诊断 -->
            <div v-if="m.clarify.options?.length" class="decide-options clarify-options-row">
              <button v-for="c in m.clarify.options" :key="c.value"
                      class="decide-btn decide-btn-primary" :disabled="sending"
                      :title="c.description || c.label"
                      @click="onOptionClick(m.clarify?.kind, c)">{{ c.label }}</button>
            </div>
            <!-- 卡片内选择 → 一次提交（点选不再倒进输入框、也不每点一条消息） -->
            <div v-if="pickCount(i) > 0" class="clarify-submit">
              <button class="decide-btn decide-btn-primary" :disabled="sending" @click="submitPicks(i)">
                {{ m.clarify.kind === 'CONFIRM' ? '提交修改' : '提交' }}（{{ pickCount(i) }} 项）
              </button>
              <button class="clarify-opt" :disabled="sending" @click="clearPicks(i)">清空选择</button>
            </div>
            <div class="clarify-foot">{{ m.clarify.kind === 'CONFIRM'
              ? (pickCount(i) > 0 ? '先「提交修改」，确认单会按新值刷新；再点「确认，开始排查」'
                  : '确认无误就点上面的按钮开始排查；要改哪项点「改」或「清空」')
              : '点候选值即在卡片里选中（可多选），改完点「提交」；也可以直接在输入框里打字回答' }}</div>
          </div>
          <!-- 决策移交卡片（人在环中 DECIDE）：证据要点 + 点选项；点选回传 #decision:value，
               自由文本回复同样生效（后端按「继续排查」消化）；点选后卡片退场防重复。
               主操作（继续排查）实心强调，终止为弱化次要操作——防止误触。 -->
          <div v-else-if="m.clarify" class="decide-card" :class="{ stale: i < messages.length - 1 }">
            <div class="hitl-eyebrow decide-eyebrow">
              <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
                <path d="M12 8.5v4" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" />
                <circle cx="12" cy="16.8" r="1.3" fill="currentColor" />
                <path d="M10.3 4.2 3.6 15.6c-.7 1.2.2 2.7 1.6 2.7h13.6c1.4 0 2.3-1.5 1.6-2.7L13.7 4.2c-.7-1.2-2.7-1.2-3.4 0Z"
                      stroke="currentColor" stroke-width="1.9" stroke-linejoin="round" />
              </svg>
              排查需要你的判断
            </div>
            <div class="decide-summary">{{ m.clarify.summary }}</div>
            <!-- 竞争假设（P0 结构化决策面）：用户看到的是完整假设空间与每条的判别动作，
                 而不是一段「卡住了」的描述；已排除的也列（「不用再试」本身就是信息） -->
            <ul v-if="m.clarify.hypotheses?.length" class="decide-hypotheses">
              <li v-for="(h,hi) in m.clarify.hypotheses" :key="hi" class="decide-hyp">
                <div class="decide-hyp-head">
                  <span class="decide-hyp-no num">{{ 'h' + (hi + 1) }}</span>
                  <span class="decide-hyp-status" :class="'status-' + h.status">{{ hypStatusLabel(h.status) }}</span>
                  <span class="decide-hyp-claim">{{ h.claim }}</span>
                </div>
                <div v-if="h.evidence" class="decide-hyp-line">{{ h.evidence }}</div>
                <div class="decide-hyp-line decide-hyp-next">判别动作：{{ h.nextAction }}</div>
              </li>
            </ul>
            <ul v-if="m.clarify.evidence?.length" class="decide-evidence">
              <li v-for="(e,ei) in m.clarify.evidence" :key="ei">
                <span class="num decide-ev-no">{{ ei + 1 }}</span>
                <span class="decide-ev-text">{{ e }}</span>
              </li>
            </ul>
            <div v-if="m.clarify.options?.length" class="decide-options">
              <button v-for="c in m.clarify.options" :key="c.value"
                      class="decide-btn" :class="{ 'decide-btn-primary': c.value !== 'terminate' }"
                      :disabled="sending"
                      :title="c.description || c.label"
                      @click="decideAction(c)">{{ c.label }}</button>
            </div>
            <div class="decide-hint">补充信息（traceId / 时间 / 报文 / 你的怀疑方向）后发送即可续跑；也可以直接终止</div>
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
                {{ p.label }}
              </a-select-option>
            </a-select>
            <ParadigmHelp :options="chatParadigmOptions()" />
          </div>
          <div class="composer-paradigm">
            <span class="bar-label">自主</span>
            <a-select v-model:value="autonomy" size="small" class="bar-select" :disabled="sending"
                      :popup-match-select-width="false" title="会话自主档位（人在环中）">
              <a-select-option v-for="o in AUTONOMY_OPTIONS" :key="o.value || 'default'" :value="o.value"
                               :title="o.desc">
                {{ o.label }}
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

    <!-- 轨迹回看抽屉（复用 Agent 对照面板的轨迹树；宽度可拖，默认 720）
         手柄是抽屉左缘上的一条全高抓取带：抽屉贴着右边，所以「指针到窗口右缘的距离」
         就是宽度。用 fixed 定位跟随宽度而不是塞进抽屉内部——塞进去就得和 antd 的
         body padding / header 层叠较劲，还要提防被 overflow 裁掉。 -->
    <div
      v-if="traceDrawerOpen"
      class="trace-resize-handle"
      :style="{ right: `${traceDrawerWidth}px` }"
      role="separator"
      tabindex="0"
      aria-orientation="vertical"
      aria-label="调整轨迹抽屉宽度，双击恢复默认"
      @pointerdown="startTraceResize"
      @dblclick="resetTraceWidth"
      @keydown.left.prevent="nudgeTraceResize(-40)"
      @keydown.right.prevent="nudgeTraceResize(40)"
    />
    <a-drawer
      :open="traceDrawerOpen"
      :width="traceDrawerWidth"
      title="Agent 执行轨迹"
      :body-style="{ padding: '16px 18px' }"
      @update:open="(v: boolean) => (traceDrawerOpen = v)"
    >
      <a-spin :spinning="traceLoading">
        <AgentTraceTree
          v-if="drawerTrace"
          :trace="drawerTrace"
          :question="drawerQuestion"
          :citations="drawerCitations"
        />
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

/* 轨迹抽屉的拖宽手柄：贴着抽屉左缘的一条透明抓取带，hover/聚焦时才显形——
   常驻的竖线在每一轮对话旁边都插一根，比它能帮上的忙更吵。 */
.trace-resize-handle {
  position: fixed; top: 0; bottom: 0; width: 8px;
  z-index: 1001; /* antd 抽屉内容层的 z-index 是 1000，手柄要浮在它上面才抓得到 */
  cursor: col-resize; outline: none;
}
.trace-resize-handle::after {
  content: ''; position: absolute; left: 3px; top: 0; bottom: 0; width: 2px;
  background: transparent; transition: background .12s ease;
}
.trace-resize-handle:hover::after,
.trace-resize-handle:focus-visible::after { background: var(--color-primary, #0064fa); }

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
/* 人在环中卡片公共：eyebrow 小标签（问齐 / 决策共用风格骨架） */
.hitl-eyebrow { display:flex; align-items:center; gap:6px; font-size:11px; font-weight:600; letter-spacing:.08em; color:var(--color-ink-tertiary); text-transform:uppercase; margin-bottom:6px; }
.hitl-eyebrow svg { width:13px; height:13px; flex-shrink:0; }

/* 运维诊断问齐卡片（clarify 事件）：问题清单 + 候选值点选 + 自动补全透出 */
.clarify-card { margin-top:10px; padding:10px 12px; background:var(--color-primary-light); border:1px solid var(--color-border-light); border-radius:var(--radius-md); }
.clarify-lead { margin:2px 0 6px; font-size:12.5px; line-height:1.65; color:var(--color-ink-secondary); }
.clarify-item { display:flex; align-items:baseline; gap:8px; flex-wrap:wrap; padding:4px 0; font-size:13px; color:var(--color-ink); }
.clarify-q { font-weight:500; }
.clarify-no { display:inline-flex; align-items:center; justify-content:center; width:16px; height:16px; margin-right:4px; font-size:10px; color:var(--color-primary); background:var(--color-surface); border:1px solid var(--color-border-light); border-radius:var(--radius-sm); transform:translateY(-1px); }
.clarify-hint { font-size:11.5px; color:var(--color-ink-tertiary); }
.clarify-options-row { padding-left:0; }
.clarify-options { display:flex; gap:6px; flex-wrap:wrap; width:100%; padding:2px 0 2px 20px; }
.clarify-opt { padding:2px 10px; font-size:12px; border:1px solid var(--color-border); border-radius:var(--radius-sm); background:var(--color-surface); color:var(--color-ink-secondary); cursor:pointer; transition:all .15s; }
.clarify-opt:hover:not(:disabled) { border-color:var(--color-primary); color:var(--color-primary); }
.clarify-opt:disabled { opacity:.5; cursor:not-allowed; }
.clarify-opt:focus-visible { outline:none; box-shadow:0 0 0 2px var(--color-focus-ring); }
.clarify-auto { margin-top:8px; padding:8px 10px; background:var(--color-surface); border:1px dashed var(--color-border-light); border-radius:var(--radius-sm); }
.clarify-auto-title { font-size:11px; font-weight:600; letter-spacing:.05em; color:var(--color-ink-tertiary); margin-bottom:4px; }
.clarify-auto-line { font-size:12px; line-height:1.6; color:var(--color-ink-secondary); word-break:break-word; }
/* 一行信息：槽位 → 值 → 来源角标 ……（右推）动作；依据独占第二行。
   值不抢满整行（曾把角标顶到 600px 外，读起来是断的） */
.auto-row { display:flex; align-items:baseline; gap:6px; flex-wrap:wrap; padding:3px 0; font-size:12px; color:var(--color-ink-secondary); }
.auto-row + .auto-row { border-top:1px dashed var(--color-border-light); padding-top:6px; margin-top:2px; }
.auto-slot { flex:0 0 auto; min-width:48px; color:var(--color-ink); font-weight:500; }
.auto-value { flex:0 1 auto; max-width:44%; min-width:0; font-family:var(--font-mono, monospace); font-size:11.5px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;
  cursor:pointer; border-bottom:1px dashed transparent; transition:color .12s ease, border-color .12s ease; }
.auto-value:hover { color:var(--color-primary); border-bottom-color:var(--color-primary); }
.auto-badge { flex:0 0 auto; padding:0 6px; font-size:10.5px; line-height:16px; border-radius:99px; border:1px solid var(--color-border-light); color:var(--color-ink-tertiary); background:var(--color-surface); }
.auto-badge-llm { color:var(--color-warning-ink, var(--color-primary)); border-color:currentColor; }
.auto-badge-log_query { color:var(--color-primary); border-color:currentColor; }
.auto-badge-user_override { color:var(--color-ink-secondary); border-color:currentColor; }
/* 只读值（用户自己给的）：去掉「可点」的视觉暗示——它没有编辑入口 */
.auto-value.readonly { cursor:default; }
.auto-value.readonly:hover { color:inherit; border-bottom-color:transparent; }
.auto-fix { display:flex; gap:4px; flex-wrap:wrap; margin-left:auto; align-items:center; }
/* 改 = 主色文字动作（低噪，hover 才出边框）；清空 = 危险语汇（平时灰，hover 转警示色） */
.auto-fix .auto-act { padding:0 7px; font-size:11px; line-height:17px; color:var(--color-primary); border-color:transparent; background:transparent; }
.auto-fix .auto-act:hover:not(:disabled) { border-color:var(--color-primary); background:var(--color-primary-light); }
.auto-fix .auto-act-clear { color:var(--color-ink-tertiary); }
.auto-fix .auto-act-clear:hover:not(:disabled) { color:var(--color-warning-ink, #d46b08); border-color:var(--color-warning-ink, #d46b08); background:transparent; }
/* 候选替换值：虚线胶囊 = "建议值"（与问句候选值的实线胶囊区分：那是"你的回答"，这是"系统的推荐"） */
.auto-fix .auto-opt { padding:0 8px; font-size:11px; line-height:17px; border-style:dashed; color:var(--color-primary); }
.auto-fix .auto-opt:hover:not(:disabled) { border-style:solid; background:var(--color-primary-light); }
/* 选中态：卡片内待提交（主色实心感，与"点一下就走"的老行为区分） */
.clarify-opt.picked { border-style:solid; border-color:var(--color-primary); background:var(--color-primary-light); color:var(--color-primary); font-weight:600; }
.clarify-opt.picked::before { content:'✓ '; }
.auto-value.picked { color:var(--color-primary); font-weight:600; border-bottom-color:var(--color-primary); }
/* 卡片底部提交行：选了几项就出现，一次提交 */
/* 诊断结论：上下文来源清单 + 代码块复制条（结论里的修复报文） */
.conclusion-context { margin-top:10px; padding:8px 10px; background:var(--color-surface-secondary); border:1px solid var(--color-border-light); border-radius:var(--radius-sm); }
.conclusion-context-title { font-size:11px; font-weight:600; letter-spacing:.05em; color:var(--color-ink-tertiary); margin-bottom:5px; }
.conclusion-context-row { display:flex; align-items:baseline; gap:6px; padding:2px 0; font-size:11.5px; color:var(--color-ink-secondary); }
.conclusion-context-evidence { flex:1 1 auto; min-width:0; color:var(--color-ink-tertiary); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.code-block { position:relative; margin:8px 0; }
.code-bar { display:flex; justify-content:flex-end; margin-bottom:-6px; }
.code-copy { padding:0 8px; font-size:11px; line-height:18px; color:var(--color-ink-tertiary); background:var(--color-surface); border:1px solid var(--color-border-light); border-radius:var(--radius-sm); cursor:pointer; }
.code-copy:hover { color:var(--color-primary); border-color:var(--color-primary); }
/* 历史卡片（不再是最后一条 = 已翻页的旧交互）：可读但不可点，避免对着早已答复过的卡片操作 */
.clarify-card.stale .clarify-opt, .clarify-card.stale .decide-btn,
.clarify-card.stale .auto-value, .decide-card.stale .decide-btn { pointer-events:none; opacity:.6; cursor:default; }
.clarify-card.stale .clarify-foot::before { content:'历史记录 · '; color:var(--color-ink-tertiary); }
.clarify-submit { display:flex; align-items:center; gap:8px; margin-top:10px; padding-top:10px; border-top:1px dashed var(--color-border-light); }
.auto-evidence { flex-basis:100%; padding-left:54px; font-size:11px; color:var(--color-ink-tertiary); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.auto-input { flex:1 1 160px; min-width:0; padding:2px 8px; font-size:12px; font-family:var(--font-mono, monospace); color:var(--color-ink); background:var(--color-surface); border:1px solid var(--color-primary); border-radius:var(--radius-sm); outline:none; }
.clarify-foot { margin-top:8px; font-size:11.5px; color:var(--color-ink-tertiary); border-top:1px dashed var(--color-border-light); padding-top:8px; }

/* 决策移交卡片（人在环中 DECIDE）：signal 强调条 + 证据编号列表 + 主次按钮 */
.decide-card { margin-top:10px; padding:12px 14px; background:var(--color-signal-bg); border:1px solid var(--color-border-light); border-left:3px solid var(--color-signal); border-radius:var(--radius-md); display:flex; flex-direction:column; gap:8px; }
.decide-eyebrow { color:var(--color-warning-ink); margin-bottom:0; }
.decide-summary { font-size:13.5px; font-weight:600; color:var(--color-ink); line-height:1.5; }
.decide-hypotheses { margin:0; padding:0; list-style:none; display:flex; flex-direction:column; gap:8px; }
.decide-hyp { display:flex; flex-direction:column; gap:3px; padding:8px 10px; background:var(--color-surface-secondary); border-radius:var(--radius-sm); }
.decide-hyp-head { display:flex; gap:7px; align-items:baseline; }
.decide-hyp-no { flex-shrink:0; font-size:11px; font-weight:600; color:var(--color-ink-tertiary); }
.decide-hyp-status { flex-shrink:0; font-size:10.5px; padding:1px 6px; border-radius:var(--radius-sm); line-height:1.6; }
.decide-hyp-status.status-verified { color:#1f7a2b; background:var(--color-success-bg); }
.decide-hyp-status.status-disproved { color:var(--color-danger); background:var(--color-danger-bg); }
.decide-hyp-status.status-unverified { color:var(--color-warning-ink); background:var(--color-signal-bg); }
.decide-hyp-claim { font-size:12.5px; font-weight:600; color:var(--color-ink); line-height:1.5; }
.decide-hyp-line { font-size:12px; color:var(--color-ink-secondary); line-height:1.55; padding-left:2px; }
.decide-hyp-next { color:var(--color-ink-tertiary); }
.decide-evidence { margin:0; padding:0; list-style:none; display:flex; flex-direction:column; gap:4px; }
.decide-evidence li { display:flex; gap:8px; align-items:baseline; font-size:12.5px; line-height:1.55; color:var(--color-ink-secondary); }
.decide-ev-no { flex-shrink:0; width:16px; height:16px; display:inline-flex; align-items:center; justify-content:center; font-size:10px; color:var(--color-warning-ink); background:var(--color-surface); border:1px solid var(--color-border-light); border-radius:var(--radius-sm); }
.decide-ev-text { min-width:0; word-break:break-word; }
.decide-options { display:flex; gap:8px; flex-wrap:wrap; margin-top:2px; }
.decide-btn { padding:6px 14px; font-size:13px; font-weight:500; border:1px solid var(--color-border); border-radius:var(--radius-md); background:var(--color-surface); color:var(--color-ink-secondary); cursor:pointer; transition:all .15s; }
.decide-btn:hover:not(:disabled) { border-color:var(--color-danger); color:var(--color-danger); }
/* 主操作（继续排查）：实心 primary；次操作（终止）：hover 才显 danger，弱化防误触 */
.decide-btn-primary { background:var(--color-primary); border-color:var(--color-primary); color:#fff; }
.decide-btn-primary:hover:not(:disabled) { background:var(--color-primary-hover); border-color:var(--color-primary-hover); color:#fff; }
.decide-btn:disabled { opacity:.5; cursor:not-allowed; }
.decide-btn:focus-visible { outline:none; box-shadow:0 0 0 2px var(--color-focus-ring); }
.decide-hint { font-size:11.5px; color:var(--color-ink-tertiary); border-top:1px dashed var(--color-border-light); padding-top:8px; }
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
