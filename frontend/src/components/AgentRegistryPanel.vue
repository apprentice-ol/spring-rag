<script setup lang="ts">
/**
 * Agent 清单页：骨架注册表（GET /agent/registry）+ Prompt 绑定状态（/prompt-bundle 拼接）。
 * 展开区按框架组合结构分区：Agent 层（身份/能力上界/人格 prompt）× Workflow 层
 * （槽位目录/阶段流程/执行策略/流程 prompt）——范式 = 两者 Bridge 组合，不是实现关系。
 * 骨架是代码注册（编译期），本页为只读配置视图；「新增 Agent」给扩展故事指引。
 */
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  ApartmentOutlined,
  DownOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { getRegistry, type AgentView, type StageView } from '../api/agentRegistry'
import { listBindings, listBundles } from '../api/bundle'

const loading = ref(false)
const registry = ref<{ defaultAgent: string; agents: AgentView[] } | null>(null)
const bindingText = ref<Record<string, string>>({})
const expanded = ref(new Set<string>())

async function load() {
  loading.value = true
  try {
    const [{ data: reg }, { data: bundles }, { data: bindings }] = await Promise.all([
      getRegistry(),
      listBundles().catch(() => ({ data: [] })),
      listBindings().catch(() => ({ data: [] })),
    ])
    registry.value = reg
    const nameOf = (id: number | null) =>
      id == null ? null : bundles.find((b) => b.id === id)?.name ?? `#${id}`
    const map: Record<string, string> = {}
    for (const a of reg.agents) {
      const b = bindings.find((x) => x.agentType === a.type)
      if (!b) {
        map[a.type] = '基线（classpath）'
      } else {
        const parts = [nameOf(b.baseBundleId), nameOf(b.overlayBundleId)].filter(Boolean) as string[]
        map[a.type] = parts.length ? parts.join(' + ') : '基线（classpath）'
      }
    }
    bindingText.value = map
  } catch {
    message.error('加载 Agent 清单失败')
  } finally {
    loading.value = false
  }
}

function toggle(type: string) {
  const s = new Set(expanded.value)
  if (s.has(type)) s.delete(type)
  else s.add(type)
  expanded.value = s
}

const NODE_KIND_TEXT: Record<string, string> = {
  LOOP: '工具循环',
  DETERMINISTIC: '确定性',
  AGENT_CALL: '子 Agent',
}

function kindText(k: string): string {
  return NODE_KIND_TEXT[k] ?? k
}

function stageKeys(a: AgentView): StageView[] {
  return a.stageDetails ?? []
}

/** 阶段节点约束 chips（护栏/条件/步数/策略/子Agent） */
function stageGuards(s: StageView): string[] {
  const out: string[] = []
  if (s.nodeKind === 'LOOP') out.push(`≤ ${s.maxSteps} 步`)
  if (s.guard) out.push('产出护栏')
  if (s.conditional) out.push('条件执行')
  if (s.errorPolicy && s.errorPolicy !== 'AS_IS') out.push(s.errorPolicy)
  if (s.targetAgentId) out.push(`→ ${s.targetAgentId}`)
  return out
}

function policyChips(a: AgentView): string[] {
  const p = a.policy
  if (!p) return []
  const chips: string[] = []
  chips.push(p.replan ? `replan 检查点（adjust ≤ ${p.maxAdjustRetries}）` : '无 replan（纯顺序）')
  chips.push(p.maxLlmCalls > 0 ? `LLM 预算 ${p.maxLlmCalls} 次` : 'LLM 预算不限')
  chips.push(p.timeoutSeconds > 0 ? `超时 ${p.timeoutSeconds}s` : '超时不限')
  return chips
}

/** 跳到 Prompt 资产页选中该 key（跨页联动） */
function goPrompt(key: string) {
  window.location.hash = '#/admin/prompt?key=' + encodeURIComponent(key)
}

const showGuide = ref(false)

onMounted(load)
const agentCount = computed(() => registry.value?.agents.length ?? 0)
</script>

<template>
  <div class="page-scroll agents-page">
    <!-- 页头 -->
    <div class="page-header">
      <div>
        <span class="eyebrow">Agent Registry</span>
        <h1 class="page-title">Agent 清单</h1>
        <p class="page-desc">
          {{ agentCount }} 个骨架在册 · 默认范式
          <b class="num">{{ registry?.defaultAgent || '—' }}</b>
          · 范式 = Agent（身份）× Workflow（流程）组合 · 代码注册、配置只读
        </p>
      </div>
      <div class="page-actions">
        <a-button size="small" title="刷新" @click="load">
          <template #icon><ReloadOutlined :spin="loading" /></template>
        </a-button>
      </div>
    </div>

    <a-spin :spinning="loading">
      <!-- Agent 卡片 -->
      <div
        v-for="a in registry?.agents ?? []"
        :key="a.type"
        class="agent-card"
        :class="{ open: expanded.has(a.type) }"
      >
        <button class="agent-head" @click="toggle(a.type)">
          <div class="agent-title">
            <span class="agent-label">{{ a.label }}</span>
            <span class="agent-type num">{{ a.type }}</span>
            <span v-if="a.type === registry?.defaultAgent" class="def-pill">默认</span>
          </div>
          <p class="agent-desc">{{ a.description }}</p>
          <div class="agent-metrics">
            <span class="metric"><ApartmentOutlined class="m-ic" />{{ a.stages.length }} 阶段</span>
            <span class="metric"><ThunderboltOutlined class="m-ic" />{{ a.tools.length }} 工具</span>
            <span v-if="a.slots?.length" class="metric">{{ a.slots.length }} 槽位</span>
            <span v-if="a.policy" class="metric" :class="{ ok: a.policy.replan }">
              {{ a.policy.replan ? 'replan ✓' : '无 replan' }}
            </span>
            <span class="metric bind">Prompt：{{ bindingText[a.type] || '—' }}</span>
            <DownOutlined class="expand-ic" :class="{ open: expanded.has(a.type) }" />
          </div>
        </button>

        <!-- 展开详情：Agent 层 × Workflow 层 -->
        <div v-if="expanded.has(a.type)" class="agent-body">
          <!-- 组合条：Bridge 组合关系 -->
          <div class="compose-bar">
            <span class="cb-side"><UserOutlined class="cb-ic" />Agent <code class="num">{{ a.type }}</code></span>
            <span class="cb-x">×</span>
            <span class="cb-side"><ApartmentOutlined class="cb-ic" />Workflow <code class="num">{{ a.workflowId || '—' }}</code></span>
            <span class="cb-note">两层独立扩展，靠组合出范式（非实现关系）</span>
          </div>

          <div class="ab-grid">
            <!-- ── Agent 层 ── -->
            <section class="layer agent-layer">
              <h5 class="layer-title"><UserOutlined /> Agent 层 · 身份与人格</h5>
              <div class="kv">
                <span class="kv-k">意图域</span>
                <span class="kv-v num">{{ a.intentDomain || '—' }}</span>
              </div>
              <div class="kv">
                <span class="kv-k">能力上界</span>
                <span class="kv-v">
                  <span v-for="c in a.capabilities ?? []" :key="c" class="cap-chip num">{{ c }}</span>
                  <span v-if="!a.capabilities?.length" class="st-none">—</span>
                </span>
              </div>
              <div class="layer-block">
                <span class="blk-label">人格 prompt（角色 / 语气 / 交付风格）</span>
                <div v-if="a.agentPromptKeys?.length" class="pk-wrap">
                  <code
                    v-for="k in a.agentPromptKeys"
                    :key="k"
                    class="pk-chip num pk-link"
                    title="在 Prompt 资产中查看"
                    @click="goPrompt(k)"
                  >{{ k }}</code>
                </div>
                <p v-else class="blk-empty">无人格层 prompt——人格由链路层（chat/）承载</p>
              </div>
            </section>

            <!-- ── Workflow 层 ── -->
            <section class="layer wf-layer">
              <h5 class="layer-title"><ApartmentOutlined /> Workflow 层 · 流程与策略</h5>

              <!-- 阶段流程可视化 -->
              <div class="layer-block">
                <span class="blk-label">阶段骨架（顺序执行；⟳ = replan 检查点）</span>
                <div v-if="stageKeys(a).length" class="stage-flow">
                  <template v-for="(s, i) in stageKeys(a)" :key="s.name">
                    <div class="flow-node" :class="s.nodeKind">
                      <div class="fn-head">
                        <span class="fn-idx num">{{ i + 1 }}</span>
                        <span class="fn-name num">{{ s.name }}</span>
                      </div>
                      <span class="kind-pill" :class="s.nodeKind">{{ kindText(s.nodeKind) }}</span>
                      <div v-if="s.systemPromptKey" class="fn-key num">{{ s.systemPromptKey }}</div>
                      <div v-if="s.tools.length" class="fn-tools">
                        <code v-for="t in s.tools" :key="t" class="tool-chip num">{{ t }}</code>
                      </div>
                      <div v-if="stageGuards(s).length" class="fn-guards">
                        <span v-for="g in stageGuards(s)" :key="g" class="guard-chip num">{{ g }}</span>
                      </div>
                    </div>
                    <div
                      v-if="i < stageKeys(a).length - 1"
                      class="flow-arrow"
                      :title="a.policy?.replan ? 'replan 检查点：continue / adjust / escalate' : '顺序推进'"
                    >
                      →<span v-if="a.policy?.replan" class="replan-mark">⟳</span>
                    </div>
                  </template>
                </div>
                <!-- 旧后端兜底：阶段名序列 -->
                <div v-else class="stage-fallback">
                  <span v-for="(s, i) in a.stages" :key="s" class="stage-chip">
                    <b class="num">{{ i + 1 }}</b> {{ s }}
                  </span>
                </div>
              </div>

              <!-- 槽位目录 -->
              <div v-if="a.slots?.length" class="layer-block">
                <span class="blk-label">槽位目录（缺必填项触发一次问齐）</span>
                <div class="slot-table">
                  <div class="slot-row slot-head"><span>槽位</span><span>必填</span><span>追问话术</span><span>提示</span></div>
                  <div v-for="s in a.slots" :key="s.name" class="slot-row">
                    <span class="num st-name">{{ s.name }}</span>
                    <span><span v-if="s.required" class="req-pill">必填</span><span v-else class="st-none">选填</span></span>
                    <span class="slot-q">{{ s.question }}</span>
                    <span class="slot-hint">{{ s.hint || '—' }}</span>
                  </div>
                </div>
              </div>

              <!-- 执行策略 -->
              <div class="layer-block">
                <span class="blk-label">执行策略</span>
                <div class="policy-chips">
                  <span v-for="c in policyChips(a)" :key="c" class="policy-chip">{{ c }}</span>
                </div>
              </div>

              <!-- 流程 prompt -->
              <div v-if="a.workflowPromptKeys?.length" class="layer-block">
                <span class="blk-label">流程 prompt（阶段 system / 抽槽 / replan / 答案）—— 点击跳转资产页</span>
                <div class="pk-wrap">
                  <code
                    v-for="k in a.workflowPromptKeys"
                    :key="k"
                    class="pk-chip num pk-link"
                    title="在 Prompt 资产中查看"
                    @click="goPrompt(k)"
                  >{{ k }}</code>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>

      <!-- 新增指引 -->
      <div class="guide-card">
        <button class="guide-head" @click="showGuide = !showGuide">
          <span>新增 Agent = 代码骨架（刻意不做运行时动态创建）</span>
          <DownOutlined class="expand-ic" :class="{ open: showGuide }" />
        </button>
        <div v-if="showGuide" class="guide-body">
          <p class="g-desc">范式 = (Agent × Workflow) 的代码组合，注册点是刻意的（路由可枚举、指纹可追溯）。三步：</p>
          <ol class="g-steps">
            <li><b>声明骨架</b>——<code class="num">impl</code> 包下 <code class="num">@Component implements WorkflowAgent</code>，definition 声明 agentType / 槽位 / 阶段 / promptKey / replanPromptKey</li>
            <li><b>注册路由</b>——<code class="num">RagParadigm</code> 枚举加值（唯一注册点，pipeline 路由零改）</li>
            <li><b>前端选项</b>——范式选择器自动从 <code class="num">/agent/registry</code> 拉取，无需改前端代码</li>
          </ol>
          <p class="g-hint">prompt 内容的调整不需要动骨架：在「Prompt 资产」发新版本，或建能力包绑定到该 agent。</p>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.agents-page {
  gap: 0;
}

/* ── Agent 卡片 ── */
.agent-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  margin-bottom: 12px;
  overflow: hidden;
}
.agent-card.open {
  border-color: var(--color-primary);
}
.agent-head {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  padding: 14px 18px;
  border: none;
  background: none;
  cursor: pointer;
  text-align: left;
}
.agent-head:hover {
  background: var(--color-hover-tint);
}
.agent-title {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.agent-label {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--color-ink);
}
.agent-type {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.def-pill {
  font-size: 10.5px;
  font-weight: 500;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
}
.agent-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--color-ink-secondary);
}
.agent-metrics {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 2px;
}
.metric {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 1px 9px;
  line-height: 18px;
}
.metric .m-ic {
  font-size: 10px;
}
.metric.ok {
  color: var(--color-success);
  background: var(--color-success-bg);
}
.metric.bind {
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.expand-ic {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-ink-tertiary);
  transition: transform 0.18s;
}
.expand-ic.open {
  transform: rotate(180deg);
}

/* ── 展开详情 ── */
.agent-body {
  padding: 12px 18px 16px;
  border-top: 1px solid var(--color-border-light);
}

/* 组合条：Agent × Workflow */
.compose-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 8px 14px;
  margin-bottom: 12px;
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  font-size: 12.5px;
}
.cb-side {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-ink);
}
.cb-side code {
  font-size: 12px;
  color: var(--color-primary);
}
.cb-ic {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.cb-x {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink-tertiary);
}
.cb-note {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--color-ink-tertiary);
}

/* 双栏：Agent 层 | Workflow 层 */
.ab-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}
@media (min-width: 1000px) {
  .ab-grid {
    grid-template-columns: minmax(240px, 5fr) minmax(420px, 9fr);
    align-items: start;
  }
}
.layer {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 12px 14px;
}
.layer-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 10px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--color-ink);
}
.layer-title :deep(.anticon) {
  font-size: 12px;
  color: var(--color-primary);
}
.agent-layer {
  background: linear-gradient(180deg, var(--color-hover-tint), transparent 90px);
}
.kv {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid var(--color-border-light);
  font-size: 12px;
}
.kv-k {
  flex-shrink: 0;
  width: 64px;
  color: var(--color-ink-tertiary);
}
.kv-v {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  min-width: 0;
}
.cap-chip {
  font-size: 10.5px;
  color: var(--color-ink-secondary);
  border: 1px dashed var(--color-border);
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
}
.layer-block {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.blk-label {
  font-size: 11.5px;
  color: var(--color-ink-tertiary);
}
.blk-empty {
  margin: 0;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

/* ── 阶段流程可视化 ── */
.stage-flow {
  display: flex;
  align-items: stretch;
  gap: 0;
  overflow-x: auto;
  padding: 4px 2px 8px;
}
.flow-node {
  flex-shrink: 0;
  width: 176px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 9px 11px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.flow-node.LOOP {
  border-color: var(--color-primary);
}
.flow-node.AGENT_CALL {
  border-color: var(--color-signal);
}
.flow-node.DETERMINISTIC {
  background: var(--color-surface-secondary);
}
.fn-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
}
.fn-idx {
  font-size: 11px;
  font-weight: 700;
  color: var(--color-primary);
  flex-shrink: 0;
}
.fn-name {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--color-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kind-pill {
  align-self: flex-start;
  font-size: 10.5px;
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
  background: var(--color-surface-secondary);
  color: var(--color-ink-secondary);
}
.flow-node.LOOP .kind-pill {
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.flow-node.AGENT_CALL .kind-pill {
  color: var(--color-warning-ink);
  background: var(--color-signal-bg);
}
.fn-key {
  font-size: 10px;
  color: var(--color-ink-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fn-tools {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.tool-chip {
  font-size: 10.5px;
  color: var(--color-ink);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-sm);
  padding: 0 6px;
  line-height: 17px;
}
.flow-node.DETERMINISTIC .tool-chip {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
}
.fn-guards {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.guard-chip {
  font-size: 10px;
  color: var(--color-ink-secondary);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 16px;
}
.flow-arrow {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 6px;
  color: var(--color-ink-tertiary);
  font-size: 15px;
  font-weight: 600;
}
.replan-mark {
  font-size: 12px;
  color: var(--color-warning-ink);
}

/* 旧后端兜底 */
.stage-fallback {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.stage-chip {
  font-size: 12px;
  color: var(--color-ink-secondary);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-sm);
  padding: 3px 10px;
}
.stage-chip b {
  color: var(--color-primary);
}

/* ── 槽位表 ── */
.slot-table {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.slot-row {
  display: grid;
  grid-template-columns: 130px 52px minmax(200px, 1.4fr) minmax(140px, 1fr);
  gap: 10px;
  padding: 6px 12px;
  font-size: 12px;
  border-bottom: 1px solid var(--color-border-light);
  align-items: center;
}
.slot-row:last-child {
  border-bottom: none;
}
.slot-head {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
}
.req-pill {
  font-size: 10.5px;
  color: var(--color-danger);
  background: var(--color-danger-bg);
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
}
.st-name {
  font-weight: 600;
  color: var(--color-ink);
}
.slot-q,
.slot-hint {
  color: var(--color-ink-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.slot-hint {
  color: var(--color-ink-tertiary);
}
.st-none {
  color: var(--color-ink-tertiary);
}

/* ── 策略 / prompt ── */
.policy-chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.policy-chip {
  font-size: 11.5px;
  color: var(--color-ink-secondary);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-sm);
  padding: 2px 10px;
}
.pk-wrap {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}
.pk-chip {
  font-size: 10.5px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: var(--radius-sm);
  padding: 1px 8px;
  line-height: 17px;
}
.pk-link {
  cursor: pointer;
  transition: background 0.12s, color 0.12s;
}
.pk-link:hover {
  background: var(--color-primary);
  color: #fff;
}

/* 新增指引 */
.guide-card {
  background: var(--color-surface);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  margin-top: 4px;
}
.guide-head {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 12px 18px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-ink-secondary);
  text-align: left;
}
.guide-head:hover {
  color: var(--color-ink);
}
.guide-body {
  padding: 0 18px 14px;
}
.g-desc {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--color-ink-secondary);
}
.g-steps {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12.5px;
  color: var(--color-ink-secondary);
}
.g-steps b {
  color: var(--color-ink);
}
.g-steps code {
  font-size: 11px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: var(--radius-sm);
  padding: 0 5px;
}
.g-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
</style>
