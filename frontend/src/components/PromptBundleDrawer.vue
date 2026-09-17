<script setup lang="ts">
/**
 * 能力包与绑定抽屉（/prompt-bundle）：包（组包 → 发布不可变 release）+ agent 骨架绑定
 * （基座包 + 特化包两层，同 key 特化胜出）。绑定后下一请求生效——快照层按包内容覆盖
 * classpath；未绑定骨架走基线（代码内容），行为不变。
 */
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import {
  bindAgentType,
  createBundle,
  deleteBundle,
  forkBundle,
  listBindings,
  listBundles,
  listReleases,
  publishRelease,
  unbindAgentType,
  type BundleRelease,
  type BundleView,
  type PromptBinding,
} from '../api/bundle'
import type { PromptKeyView } from '../api/prompt'
import { activeParadigms } from './evalShared'

const props = defineProps<{ open: boolean; keys: PromptKeyView[] }>()
const emit = defineEmits<{ 'update:open': [v: boolean]; changed: [] }>()

// ===== 绑定区 =====
interface BindDraft {
  base: number | undefined
  overlay: number | undefined
}

const agents = activeParadigms().map((p) => ({ value: p.value, label: p.label }))
const bindings = ref<Record<string, PromptBinding>>({})
const drafts = ref<Record<string, BindDraft>>({})
const bindError = ref<Record<string, string>>({})
const bindingBusy = ref('')

const bundles = ref<BundleView[]>([])
const loadingBundles = ref(false)

/** 未发布的包选了也没内容，选项里标出来避免误绑定 */
const bundleOptions = computed(() =>
  bundles.value.map((b) => ({
    value: b.id,
    label:
      b.name +
      (b.agentType ? ` · ${b.agentType}` : ' · 通用') +
      (b.latestRelease == null ? '（未发布）' : ` · r${b.latestRelease}`),
  })),
)

async function loadAll() {
  await Promise.all([loadBundles(), loadBindings()])
}

async function loadBundles() {
  loadingBundles.value = true
  try {
    const { data } = await listBundles()
    bundles.value = data
  } catch {
    message.error('加载能力包失败')
  } finally {
    loadingBundles.value = false
  }
}

async function loadBindings() {
  try {
    const { data } = await listBindings()
    const map: Record<string, PromptBinding> = {}
    for (const b of data) map[b.agentType] = b
    bindings.value = map
    const d: Record<string, BindDraft> = {}
    for (const a of agents) {
      const b = map[a.value]
      d[a.value] = { base: b?.baseBundleId ?? undefined, overlay: b?.overlayBundleId ?? undefined }
    }
    drafts.value = d
    bindError.value = {}
  } catch {
    message.error('加载绑定失败')
  }
}

function currentBindingText(type: string): string {
  const b = bindings.value[type]
  if (!b) return '代码内置（未绑定）'
  const name = (id: number | null) => (id == null ? '—' : bundles.value.find((x) => x.id === id)?.name ?? `#${id}`)
  return `${name(b.baseBundleId)}${b.overlayBundleId ? ' + ' + name(b.overlayBundleId) : ''}`
}

function draftDirty(type: string): boolean {
  const b = bindings.value[type]
  const d = drafts.value[type]
  if (!d) return false
  return (d.base ?? null) !== (b?.baseBundleId ?? null) || (d.overlay ?? null) !== (b?.overlayBundleId ?? null)
}

/** 受控更新绑定草稿（v-model 不能落到可选链属性上） */
function setDraft(type: string, field: 'base' | 'overlay', v: number | undefined) {
  const d = drafts.value[type] ?? { base: undefined, overlay: undefined }
  d[field] = v
  drafts.value[type] = d
}

async function saveBind(type: string) {
  const d = drafts.value[type]
  if (!d) return
  bindingBusy.value = type
  bindError.value[type] = ''
  try {
    await bindAgentType(type, d.base ?? null, d.overlay ?? null)
    message.success(`已绑定 ${type}（下一请求生效）`)
    await loadBindings()
    emit('changed')
  } catch (e) {
    bindError.value[type] = errText(e)
  } finally {
    bindingBusy.value = ''
  }
}

async function doUnbind(type: string) {
  bindingBusy.value = type
  bindError.value[type] = ''
  try {
    await unbindAgentType(type)
    message.success(`${type} 已恢复默认 prompt（下一个请求生效）`)
    await loadBindings()
    emit('changed')
  } catch (e) {
    bindError.value[type] = errText(e)
  } finally {
    bindingBusy.value = ''
  }
}

// ===== 包区：release 历史 =====
const expandedId = ref<number | null>(null)
const releases = ref<Record<number, BundleRelease[]>>({})
const loadingReleases = ref(0)

async function toggleReleases(b: BundleView) {
  if (expandedId.value === b.id) {
    expandedId.value = null
    return
  }
  expandedId.value = b.id
  if (!releases.value[b.id]) {
    loadingReleases.value = b.id
    try {
      const { data } = await listReleases(b.id)
      releases.value[b.id] = data
    } catch {
      message.error('加载 release 历史失败')
    } finally {
      loadingReleases.value = 0
    }
  }
}

function itemCount(r: BundleRelease): number {
  return Object.keys(r.items ?? {}).length
}

/** 展开某个 release 看它到底装了哪些 prompt（key 空间大，按 首段/ 归堆后排序） */
const openRelease = ref<number | null>(null)
const releaseItems = ref<Record<number, { key: string; ver: number }[]>>({})

function toggleReleaseItems(r: BundleRelease) {
  if (openRelease.value === r.id) {
    openRelease.value = null
    return
  }
  openRelease.value = r.id
  releaseItems.value[r.id] = Object.entries(r.items ?? {})
    .map(([key, ver]) => ({ key, ver }))
    .sort((a, b) => a.key.localeCompare(b.key))
}

function fmtTime(t: string | null | undefined): string {
  return t ? t.replace('T', ' ').slice(5, 16) : '—'
}

// ===== 建包 =====
const createOpen = ref(false)
const cName = ref('')
const cDesc = ref('')
const cAgentType = ref<string | undefined>(undefined)
const creating = ref(false)

function openCreate() {
  cName.value = ''
  cDesc.value = ''
  cAgentType.value = undefined
  createOpen.value = true
}

async function submitCreate() {
  if (!cName.value.trim()) {
    message.warning('包名不能为空')
    return
  }
  creating.value = true
  try {
    await createBundle(cName.value.trim(), cDesc.value.trim(), cAgentType.value)
    message.success(`已创建包 ${cName.value.trim()}`)
    createOpen.value = false
    await loadBundles()
    emit('changed')
  } catch (e) {
    message.error(errText(e))
  } finally {
    creating.value = false
  }
}

// ===== fork =====
const forkTarget = ref<BundleView | null>(null)
const forkName = ref('')
const forking = ref(false)

function openFork(b: BundleView) {
  forkTarget.value = b
  forkName.value = b.name + '-copy'
}

async function submitFork() {
  if (!forkTarget.value || !forkName.value.trim()) return
  forking.value = true
  try {
    await forkBundle(forkTarget.value.id, forkName.value.trim())
    message.success(`已复制为 ${forkName.value.trim()}（含最新 release）`)
    forkTarget.value = null
    await loadBundles()
    emit('changed')
  } catch (e) {
    message.error(errText(e))
  } finally {
    forking.value = false
  }
}

// ===== 删除包（后端在仍被绑定时会拒绝，错误信息直接透出） =====
async function doDelete(b: BundleView) {
  try {
    await deleteBundle(b.id)
    message.success(`已删除包 ${b.name}`)
    delete releases.value[b.id]
    if (expandedId.value === b.id) expandedId.value = null
    await Promise.all([loadBundles(), loadBindings()])
    emit('changed')
  } catch (e) {
    message.error(errText(e))
  }
}

// ===== 发布 release：勾选 key（默认全选，各取当前版本） =====
const publishTarget = ref<BundleView | null>(null)
const publishKeys = ref<Record<string, boolean>>({})
const publishNote = ref('')
const publishing = ref(false)

/** 只列已建档 key（codeOnly 无版本不可入包） */
const publishableKeys = computed(() => props.keys.filter((k) => !k.codeOnly && k.currentVersion != null))

/**
 * 发布弹窗按「链路 → 环节」分组（28 个 key 平铺难扫，分组后可整组勾选）。
 * 归类复用后端 CHAIN_DEFS 给出的 chain/chainLabel/segmentLabel，与 PromptManage 左栏树同源。
 */
const publishGroups = computed(() => {
  const map = new Map<string, { label: string; keys: PromptKeyView[] }>()
  for (const k of publishableKeys.value) {
    const code = k.chain ?? 'other'
    if (!map.has(code)) map.set(code, { label: k.chainLabel ?? '其他', keys: [] })
    map.get(code)!.keys.push(k)
  }
  return [...map.entries()]
    .map(([code, g]) => ({
      code,
      label: g.label,
      keys: g.keys
        .slice()
        .sort(
          (a, b) =>
            (a.segmentLabel ?? '').localeCompare(b.segmentLabel ?? '') ||
            a.promptKey.localeCompare(b.promptKey),
        ),
      picked: g.keys.filter((k) => publishKeys.value[k.promptKey]).length,
    }))
    .sort((a, b) => a.code.localeCompare(b.code))
})

function setGroup(code: string, v: boolean) {
  const g = publishGroups.value.find((x) => x.code === code)
  if (!g) return
  for (const k of g.keys) publishKeys.value[k.promptKey] = v
}

function openPublish(b: BundleView) {
  publishTarget.value = b
  publishNote.value = ''
  const m: Record<string, boolean> = {}
  for (const k of publishableKeys.value) m[k.promptKey] = true
  publishKeys.value = m
}

const publishSelectedCount = computed(() => Object.values(publishKeys.value).filter(Boolean).length)

async function submitPublish() {
  const b = publishTarget.value
  if (!b) return
  const items: Record<string, number> = {}
  for (const k of publishableKeys.value) {
    if (publishKeys.value[k.promptKey]) items[k.promptKey] = k.currentVersion as number
  }
  if (Object.keys(items).length === 0) {
    message.warning('至少勾选一个 key')
    return
  }
  publishing.value = true
  try {
    const { data } = await publishRelease(b.id, items, publishNote.value)
    message.success(`已发布 ${b.name}@r${data.releaseNo}（${Object.keys(items).length} 个 key）`)
    publishTarget.value = null
    delete releases.value[b.id]
    expandedId.value = b.id
    await Promise.all([loadBundles(), loadReleases(b.id)])
    emit('changed')
  } catch (e) {
    message.error(errText(e))
  } finally {
    publishing.value = false
  }
}

async function loadReleases(bundleId: number) {
  try {
    const { data } = await listReleases(bundleId)
    releases.value[bundleId] = data
  } catch {
    /* 展开失败静默 */
  }
}

function errText(e: unknown): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string }
  return err?.response?.data?.message ?? err?.message ?? '操作失败'
}

watch(
  () => props.open,
  (v) => {
    if (v) loadAll()
  },
)
</script>

<template>
  <a-drawer
    :open="open"
    title="能力包与绑定"
    :width="720"
    @close="emit('update:open', false)"
  >
    <!-- 引导：先说清这是什么、怎么用 -->
    <div class="pb-intro">
      <div class="eyebrow">这是什么</div>
      <p class="pb-intro-lead">
        把一组 prompt 打包成<b>能力包</b>，再把它绑定到某个 Agent 上——就能
        <b>不改代码、不用重新部署</b>地换掉这个 Agent 的提示词，下一个请求生效。
      </p>
      <ol class="pb-steps">
        <li>
          <span class="pb-step-no num">1</span>
          <span><b>建包 → 发布版本</b>：挑好每个 prompt 用哪一版，发布后内容就固定了，要改就再发一版</span>
        </li>
        <li>
          <span class="pb-step-no num">2</span>
          <span><b>绑定到 Agent</b>：基座包放全平台通用的 prompt，特化包放这个 Agent 专属的；同一个 prompt 两处都有时，特化包的生效</span>
        </li>
      </ol>
    </div>

    <!-- 绑定区：agent 骨架 → 基座包 + 特化包 -->
    <section class="bd-sec">
      <div class="bd-sec-head">
        <h4 class="bd-sec-title">Agent 绑定</h4>
        <span class="bd-sec-desc">没绑定的 Agent 用代码里内置的 prompt，行为不变</span>
      </div>
      <div v-if="bundles.length === 0" class="bd-hint">
        还没有能力包可以绑定——先到下面「能力包」区建一个并发布版本
      </div>
      <div class="bind-row bind-head">
        <span>骨架</span><span>基座包</span><span>特化包</span><span></span>
      </div>
      <div v-for="a in agents" :key="a.value" class="bind-row">
        <div class="bind-agent">
          <span class="bind-label">{{ a.label }}</span>
          <code class="bind-type">{{ a.value }}</code>
        </div>
        <a-select
          :value="drafts[a.value]?.base"
          :options="bundleOptions"
          allow-clear
          placeholder="不绑定"
          size="small"
          class="bind-sel"
          @update:value="(v: number | undefined) => setDraft(a.value, 'base', v)"
        />
        <a-select
          :value="drafts[a.value]?.overlay"
          :options="bundleOptions"
          allow-clear
          placeholder="无特化"
          size="small"
          class="bind-sel"
          @update:value="(v: number | undefined) => setDraft(a.value, 'overlay', v)"
        />
        <div class="bind-ops">
          <a-button
            size="small"
            type="primary"
            :disabled="!draftDirty(a.value)"
            :loading="bindingBusy === a.value"
            @click="saveBind(a.value)"
          >绑定</a-button>
          <a-button
            v-if="bindings[a.value]"
            size="small"
            type="text"
            danger
            @click="doUnbind(a.value)"
          >恢复默认</a-button>
        </div>
        <div class="bind-meta">
          <span class="bind-cur" :title="'当前生效：' + currentBindingText(a.value)">
            当前：{{ currentBindingText(a.value) }}
          </span>
          <span v-if="bindError[a.value]" class="bind-err">{{ bindError[a.value] }}</span>
        </div>
      </div>
    </section>

    <!-- 包区 -->
    <section class="bd-sec">
      <div class="bd-sec-head">
        <h4 class="bd-sec-title">
          能力包
          <a-button size="small" type="primary" @click="openCreate">
            <template #icon><PlusOutlined /></template>建包
          </a-button>
          <a-button size="small" type="text" title="刷新" @click="loadBundles">
            <template #icon><ReloadOutlined :spin="loadingBundles" /></template>
          </a-button>
        </h4>
        <span class="bd-sec-desc">发布版本 = 把每个 prompt 当前用的那一版固定下来；发布后改不了，要改就再发一版。绑定的 Agent 自动跟随最新版</span>
      </div>

      <div v-if="bundles.length === 0 && !loadingBundles" class="bd-empty">
        还没有能力包——建一个，勾选要用的 prompt 发布版本，再回到上面绑定给 Agent
      </div>
      <div v-for="b in bundles" :key="b.id" class="bundle-card">
        <div class="b-head">
          <b class="b-name">{{ b.name }}</b>
          <a-tag v-if="b.agentType" class="b-tag">{{ b.agentType }}</a-tag>
          <span v-else class="b-tag-generic">通用</span>
          <span class="b-release num">{{ b.latestRelease == null ? '未发布' : 'r' + b.latestRelease }}</span>
          <span class="b-time num">{{ fmtTime(b.updateTime) }}</span>
          <div class="b-ops">
            <a-button size="small" @click="openPublish(b)">发布版本</a-button>
            <a-button size="small" @click="toggleReleases(b)">
              {{ expandedId === b.id ? '收起历史' : '发布记录' }}
            </a-button>
            <a-button size="small" type="text" @click="openFork(b)">复制</a-button>
            <a-popconfirm
              :title="`删除「${b.name}」？${b.latestRelease ? `该包的 r1~r${b.latestRelease} 共 ${b.latestRelease} 个发布版本会一并删除，` : ''}不可恢复（prompt 本身的版本历史不受影响）。`"
              ok-text="删除"
              cancel-text="取消"
              :ok-button-props="{ danger: true }"
              @confirm="doDelete(b)"
            >
              <a-button size="small" type="text" danger>删除</a-button>
            </a-popconfirm>
          </div>
        </div>
        <div v-if="b.description" class="b-desc">{{ b.description }}</div>

        <div v-if="expandedId === b.id" class="b-releases">
          <div v-if="loadingReleases === b.id" class="b-rel-empty">加载中…</div>
          <div v-else-if="!releases[b.id]?.length" class="b-rel-empty">还没发布过版本</div>
          <div v-for="r in releases[b.id]" :key="r.id" class="b-rel-wrap">
            <div class="b-rel-row">
              <span class="num b-rel-no">r{{ r.releaseNo }}</span>
              <span class="b-rel-note">{{ r.changeNote || '未填变更说明' }}</span>
              <a class="b-rel-keys num" @click="toggleReleaseItems(r)">
                {{ itemCount(r) }} 个 prompt {{ openRelease === r.id ? '▾' : '▸' }}
              </a>
              <span class="num b-rel-time">{{ fmtTime(r.createTime) }}</span>
            </div>
            <div v-if="openRelease === r.id" class="b-rel-items">
              <span v-for="it in releaseItems[r.id]" :key="it.key" class="b-rel-item">
                <span class="b-rel-item-key">{{ it.key }}</span>
                <span class="num b-rel-item-ver">v{{ it.ver }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 建包 -->
    <a-modal
      v-model:open="createOpen"
      title="建能力包"
      :confirm-loading="creating"
      ok-text="创建"
      cancel-text="取消"
      @ok="submitCreate"
    >
      <div class="modal-form">
        <p class="pub-hint">包本身只是容器，建完还要<b>发布版本</b>才有内容，之后才能绑定给 Agent。</p>
        <div class="mf-field">
          <label>包名（不能重复）</label>
          <a-input v-model:value="cName" placeholder="如 标准包 / ops 精简包" />
        </div>
        <div class="mf-field">
          <label>描述（选填）</label>
          <a-input v-model:value="cDesc" placeholder="这个包给谁用、装什么" />
        </div>
        <div class="mf-field">
          <label>指定 Agent（选填）</label>
          <a-select
            v-model:value="cAgentType"
            :options="agents.map((a) => ({ value: a.value, label: a.label + ' · ' + a.value }))"
            allow-clear
            placeholder="留空 = 通用包，任何 Agent 都能用"
          />
        </div>
      </div>
    </a-modal>

    <!-- fork -->
    <a-modal
      :open="!!forkTarget"
      :title="`复制包「${forkTarget?.name ?? ''}」`"
      :confirm-loading="forking"
      ok-text="复制"
      cancel-text="取消"
      @ok="submitFork"
      @cancel="forkTarget = null"
    >
      <div class="modal-form">
        <div class="mf-field">
          <label>新包名</label>
          <a-input v-model:value="forkName" placeholder="如 ops 精简包" @press-enter="submitFork" />
        </div>
        <p class="pub-hint">会把原包<b>最新一版的内容</b>一起复制过去，之后两个包各改各的、互不影响。</p>
      </div>
    </a-modal>

    <!-- 发布 release -->
    <a-modal
      :open="!!publishTarget"
      :title="`发布新版本 —— ${publishTarget?.name ?? ''}`"
      :confirm-loading="publishing"
      :ok-text="`发布（${publishSelectedCount} 个 prompt）`"
      cancel-text="取消"
      width="620px"
      @ok="submitPublish"
      @cancel="publishTarget = null"
    >
      <div class="modal-form">
        <p class="pub-hint">
          勾选这个包里要包含的 prompt，会把它们<b>当前生效的版本</b>打包成一个新版本。
          发布后这个版本就固定了，要调整得再发一版。
        </p>
        <div class="pub-toolbar">
          <a-button size="small" type="text" @click="publishableKeys.forEach((k) => (publishKeys[k.promptKey] = true))">全选</a-button>
          <a-button size="small" type="text" @click="publishableKeys.forEach((k) => (publishKeys[k.promptKey] = false))">全不选</a-button>
          <span class="pub-count num">{{ publishSelectedCount }} / {{ publishableKeys.length }}</span>
        </div>
        <div class="pub-keys">
          <template v-for="g in publishGroups" :key="g.code">
            <div class="pub-group">
              <a-checkbox
                :checked="g.picked > 0 && g.picked === g.keys.length"
                :indeterminate="g.picked > 0 && g.picked < g.keys.length"
                @change="(e: any) => setGroup(g.code, e.target.checked)"
              />
              <span class="pub-group-label">{{ g.label }}</span>
              <span class="pub-group-count num">{{ g.picked }}/{{ g.keys.length }}</span>
            </div>
            <label v-for="k in g.keys" :key="k.promptKey" class="pub-key">
              <a-checkbox v-model:checked="publishKeys[k.promptKey]" />
              <span class="pub-key-name">{{ k.promptKey }}</span>
              <span v-if="k.segmentLabel" class="pub-key-seg">{{ k.segmentLabel }}</span>
              <span class="pub-key-ver num">v{{ k.currentVersion }}</span>
            </label>
          </template>
        </div>
        <div class="mf-field">
          <label>这次改了什么（选填）</label>
          <a-input v-model:value="publishNote" placeholder="如：调整回答风格" />
        </div>
      </div>
    </a-modal>
  </a-drawer>
</template>

<style scoped>
/* ── 开篇引导 ── */
.pb-intro {
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  margin-bottom: 22px;
}
.pb-intro-lead {
  margin: 7px 0 10px;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--color-ink-secondary);
}
.pb-intro-lead b {
  color: var(--color-ink);
  font-weight: 600;
}
.pb-steps {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pb-steps li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 12px;
  line-height: 1.65;
  color: var(--color-ink-tertiary);
}
.pb-steps li b {
  color: var(--color-ink-secondary);
  font-weight: 600;
}
.pb-step-no {
  flex-shrink: 0;
  width: 15px;
  height: 15px;
  line-height: 15px;
  text-align: center;
  border-radius: 50%;
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 10px;
  font-weight: 700;
}

.bd-hint {
  font-size: 12px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  padding: 9px 12px;
  margin-bottom: 10px;
}

.bd-sec {
  margin-bottom: 22px;
}
.bd-sec-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.bd-sec-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-ink);
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.bd-sec-desc {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

/* ── 绑定行 ── */
.bind-row {
  display: grid;
  grid-template-columns: 150px 1fr 1fr auto;
  gap: 10px;
  align-items: center;
  padding: 9px 10px;
  border-bottom: 1px solid var(--color-border-light);
  font-size: 12.5px;
}
.bind-row:hover {
  background: var(--color-hover-bg);
}
.bind-head {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  padding: 4px 10px;
}
.bind-head:hover {
  background: none;
}
.bind-agent {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.bind-label {
  font-weight: 600;
  color: var(--color-ink);
}
.bind-type {
  font-family: var(--font-display);
  font-size: 10.5px;
  color: var(--color-ink-tertiary);
}
.bind-sel {
  width: 100%;
  min-width: 0;
}
.bind-ops {
  display: inline-flex;
  gap: 4px;
}
.bind-meta {
  grid-column: 1 / -1;
  display: flex;
  gap: 10px;
  align-items: baseline;
  min-height: 0;
}
.bind-meta:empty {
  display: none;
}
.bind-cur {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bind-err {
  font-size: 11.5px;
  color: var(--color-danger);
}

/* ── 包卡片 ── */
.bd-empty {
  padding: 26px 12px;
  text-align: center;
  font-size: 12.5px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
}
.bundle-card {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  margin-bottom: 10px;
}
.b-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.b-name {
  font-size: 13.5px;
  color: var(--color-ink);
}
.b-tag {
  margin: 0;
  flex-shrink: 0;
}
.b-tag-generic {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 0 8px;
  line-height: 17px;
}
.b-release {
  font-size: 11.5px;
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: var(--radius-sm);
  padding: 0 7px;
  line-height: 17px;
}
.b-time {
  font-size: 11px;
  color: var(--color-ink-tertiary);
}
.b-ops {
  margin-left: auto;
  display: inline-flex;
  gap: 4px;
}
.b-desc {
  margin-top: 5px;
  font-size: 12px;
  color: var(--color-ink-secondary);
}
.b-releases {
  margin-top: 8px;
  border-top: 1px dashed var(--color-border-light);
  padding-top: 6px;
}
.b-rel-empty {
  padding: 8px 0;
  text-align: center;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.b-rel-row {
  display: grid;
  grid-template-columns: 48px 1fr 96px 100px;
  gap: 8px;
  padding: 5px 4px;
  font-size: 12px;
  border-bottom: 1px solid var(--color-border-light);
}
.b-rel-row:last-child {
  border-bottom: none;
}
.b-rel-no {
  color: var(--color-primary);
  font-weight: 700;
}
.b-rel-note {
  color: var(--color-ink-secondary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.b-rel-wrap {
  border-bottom: 1px solid var(--color-border-light);
}
.b-rel-wrap:last-child {
  border-bottom: none;
}
.b-rel-wrap .b-rel-row {
  border-bottom: none;
}
.b-rel-time {
  color: var(--color-ink-tertiary);
  font-size: 11px;
  text-align: right;
}
/* 可点开的「N 个 prompt」——展开看这个版本到底装了哪些 key */
.b-rel-keys {
  color: var(--color-primary);
  font-size: 11px;
  text-align: right;
  cursor: pointer;
  user-select: none;
}
.b-rel-keys:hover {
  text-decoration: underline;
}
.b-rel-items {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 6px;
  padding: 2px 4px 9px 52px;
}
.b-rel-item {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  font-size: 11px;
  background: var(--color-surface-secondary);
  border-radius: var(--radius-sm);
  padding: 2px 7px;
}
.b-rel-item-key {
  font-family: var(--font-display);
  color: var(--color-ink-secondary);
}
.b-rel-item-ver {
  font-size: 10px;
  color: var(--color-ink-tertiary);
}

/* ── 对话框表单 ── */
.modal-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.mf-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mf-field label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--color-ink-secondary);
}
.pub-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--color-ink-secondary);
  background: var(--color-surface-secondary);
  border-radius: var(--radius-md);
  padding: 8px 12px;
}
.pub-hint b {
  color: var(--color-ink);
}
.pub-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
}
.pub-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.pub-keys {
  max-height: 340px;
  overflow-y: auto;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: 4px 10px;
  display: flex;
  flex-direction: column;
}
/* 分组头吸顶，滚动时知道自己停在哪个命名空间 */
.pub-group {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 2px;
  position: sticky;
  top: -4px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
  z-index: 1;
}
.pub-group-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-ink);
}
.pub-group-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-ink-tertiary);
}
.pub-key {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 2px;
  border-bottom: 1px solid var(--color-border-light);
  font-size: 12px;
  cursor: pointer;
  flex-shrink: 0; /* 纵向 flex 滚动容器中不被压缩 */
}
.pub-key:last-child {
  border-bottom: none;
}
.pub-key-name {
  font-family: var(--font-display);
  font-size: 11.5px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-ink);
}
.pub-key-seg {
  font-size: 10.5px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  border-radius: 999px;
  padding: 0 7px;
  line-height: 16px;
  flex-shrink: 0;
}
.pub-key-ver {
  font-size: 10.5px;
  color: var(--color-ink-tertiary);
  flex-shrink: 0;
  width: 34px;
  text-align: right;
}
</style>
