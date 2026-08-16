<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import {
  ReloadOutlined,
  ArrowRightOutlined,
  FileTextOutlined,
  DatabaseOutlined,
  MessageOutlined,
  BarChartOutlined,
  ApiOutlined,
  CloudServerOutlined,
  CloseCircleFilled,
} from '@ant-design/icons-vue'
import { getOverview, type ConsoleOverview } from '../api/console'

const loading = ref(false)
const data = ref<ConsoleOverview | null>(null)

onMounted(() => load())

async function load() {
  loading.value = true
  try {
    data.value = await getOverview()
  } catch {
    data.value = null
  } finally {
    loading.value = false
  }
}

function go(hash: string) {
  window.location.hash = hash
}
function isOnline(s: string | undefined): boolean {
  return s === 'online'
}
function statVal(n: number | undefined): string {
  if (n == null || n < 0) return '—'
  return n.toLocaleString('en-US')
}

const allOk = computed(() => {
  const s = data.value?.system
  return !!s && isOnline(s.backend) && isOnline(s.database)
})

// 核心指标 KPI（点击卡片跳对应模块）
const stats = computed(() => {
  const s = data.value?.stats
  if (!s) return []
  return [
    { label: '文档', value: statVal(s.documents), icon: FileTextOutlined, hash: '#/admin/docs' },
    { label: '向量块', value: statVal(s.chunks), icon: DatabaseOutlined, hash: '#/admin/docs' },
    { label: '会话', value: statVal(s.conversations), icon: MessageOutlined, hash: '#/' },
    { label: '评测运行', value: statVal(s.evalRuns), icon: BarChartOutlined, hash: '#/admin/eval' },
  ]
})

const retrievalParams = computed(() => {
  const c = data.value?.config
  if (!c) return []
  return [
    { k: 'topK', v: String(c.topK) },
    { k: '相似度阈值', v: String(c.similarityThreshold) },
    { k: 'recallBudget', v: String(c.recallBudget) },
    { k: 'candidateLimit', v: String(c.candidateLimit) },
    { k: 'contextTopK', v: String(c.contextTopK) },
    { k: 'RRF k', v: String(c.rrfK) },
  ]
})

const toggles = computed(() => {
  const c = data.value?.config
  if (!c) return []
  return [
    { k: 'Rerank 精排', on: c.rerankEnabled },
    { k: '关键词通道', on: c.keywordEnabled },
    { k: '联网检索', on: c.webSearchEnabled },
  ]
})

const chunkRows = computed(() => {
  const c = data.value?.config
  if (!c) return []
  return [
    { k: '分块大小', v: `${c.chunkSize} 字` },
    { k: '分块重叠', v: `${c.chunkOverlap} 字` },
  ]
})

const modelRows = computed(() => {
  const c = data.value?.config
  if (!c) return []
  return [
    { k: '对话', v: c.chatModel || '—' },
    { k: 'Embedding', v: c.embeddingModel || '—' },
    { k: 'Rerank', v: c.rerankModel || '—' },
  ]
})
</script>

<template>
  <div class="page-scroll console-page">
    <!-- 页头：标题 + 运行状态 + 刷新 -->
    <div class="page-header">
      <div>
        <h1 class="page-title">控制台</h1>
        <p class="page-desc">数据规模 · 检索管线配置 · 服务健康状态</p>
      </div>
      <div class="page-actions">
        <span v-if="data" class="live-pill" :class="{ ok: allOk }">
          <span class="live-dot"></span>{{ allOk ? '运行正常' : '存在异常' }}
        </span>
        <a-button type="text" title="刷新" @click="load">
          <template #icon><ReloadOutlined :spin="loading" /></template>
        </a-button>
      </div>
    </div>

    <a-spin :spinning="loading">
      <div v-if="data" class="console-grid">
        <!-- 左主区 -->
        <div class="main-col">
          <!-- 核心指标：KPI 卡可点击跳对应模块 -->
          <section class="card">
            <h3 class="card-title">核心指标</h3>
            <div class="kpi-grid">
              <div
                v-for="s in stats"
                :key="s.label"
                class="kpi-card"
                role="button"
                :title="'前往' + s.label + '模块'"
                @click="go(s.hash)"
              >
                <div class="kpi-head">
                  <span class="kpi-label">{{ s.label }}</span>
                  <span class="kpi-icon"><component :is="s.icon" /></span>
                </div>
                <div class="kpi-value">{{ s.value }}</div>
              </div>
            </div>
          </section>

          <!-- 检索配置 -->
          <section class="card">
            <h3 class="card-title">
              <ApiOutlined class="title-icon" />检索配置
              <a class="card-action" @click="go('#/admin/eval')">评测看板<ArrowRightOutlined /></a>
            </h3>
            <div class="cfg-two">
              <div class="cfg-params">
                <div v-for="p in retrievalParams" :key="p.k" class="cfg-row">
                  <span class="cfg-k">{{ p.k }}</span>
                  <span class="cfg-v">{{ p.v }}</span>
                </div>
              </div>
              <div class="cfg-toggles">
                <div v-for="t in toggles" :key="t.k" class="cfg-toggle">
                  <span>{{ t.k }}</span>
                  <span class="toggle-pill" :class="t.on ? 'on' : 'off'">{{ t.on ? '已启用' : '关闭' }}</span>
                </div>
              </div>
            </div>
          </section>

          <!-- 入库配置 -->
          <section class="card">
            <h3 class="card-title"><DatabaseOutlined class="title-icon" />入库配置</h3>
            <div class="ingest-chips">
              <span v-for="r in chunkRows" :key="r.k" class="ingest-chip">
                <span class="ic-k">{{ r.k }}</span><b>{{ r.v }}</b>
              </span>
            </div>
          </section>
        </div>

        <!-- 右侧 sticky 边栏 -->
        <aside class="side-col">
          <!-- 系统健康 -->
          <section class="card">
            <h3 class="card-title"><CloudServerOutlined class="title-icon" />系统健康</h3>
            <div class="health">
              <div class="kv-row">
                <span class="dot" :class="isOnline(data.system.backend) ? 'on' : 'off'"></span>
                <span class="kv-k">后端服务</span>
                <span class="kv-v">{{ isOnline(data.system.backend) ? '在线' : '离线' }}</span>
              </div>
              <div class="kv-row">
                <span class="dot" :class="isOnline(data.system.database) ? 'on' : 'off'"></span>
                <span class="kv-k">PostgreSQL</span>
                <span class="kv-v">{{ isOnline(data.system.database) ? '已连接' : '连接失败' }}</span>
              </div>
              <div class="kv-row">
                <CloudServerOutlined class="kv-glyph" />
                <span class="kv-k">运行环境</span>
                <span class="kv-v">JDK {{ data.system.javaVersion }}</span>
              </div>
              <div class="kv-row">
                <span class="kv-glyph kv-glyph-text">App</span>
                <span class="kv-k">应用</span>
                <span class="kv-v">{{ data.system.appName || '—' }}</span>
              </div>
            </div>
          </section>

          <!-- 模型 -->
          <section class="card">
            <h3 class="card-title">模型</h3>
            <div class="models">
              <div v-for="r in modelRows" :key="r.k" class="kv-row">
                <span class="kv-k">{{ r.k }}</span>
                <span class="kv-v" :title="r.v">{{ r.v }}</span>
              </div>
            </div>
          </section>
        </aside>
      </div>

      <div v-else-if="!loading" class="console-empty">
        <a-empty description="无法获取控制台数据">
          <template #image>
            <CloseCircleFilled style="font-size: 44px; color: var(--color-ink-tertiary)" />
          </template>
          <span class="empty-hint">后端 <code>/console/overview</code> 不可达</span>
        </a-empty>
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
/* 控制台：page-scroll 外壳 + 左右分栏 + 白卡细边框（Semi 风，token 全局） */
.console-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}
@media (min-width: 1080px) {
  .console-grid {
    grid-template-columns: 1fr 340px;
    align-items: start;
  }
  .side-col {
    position: sticky;
    top: 0;
  }
}
.main-col,
.side-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

/* 卡片：白底 + 细边框，无投影 */
.card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 14px 20px 16px;
}
.card-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.title-icon {
  color: var(--color-ink-tertiary);
  font-size: 14px;
}
.card-action {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--color-primary);
  cursor: pointer;
}
.card-action:hover {
  text-decoration: underline;
}

/* ── 核心指标 KPI（对齐评测概览页 kpi-card，可点击跳转） ── */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
@media (min-width: 620px) {
  .kpi-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
.kpi-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 14px 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.kpi-card:hover {
  border-color: var(--color-primary);
  background: #f7fbff;
}
.kpi-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.kpi-label {
  font-size: 12px;
  color: var(--color-ink-secondary);
  font-weight: 500;
}
/* 图标方块：primary-light / primary（设计系统铁律） */
.kpi-icon {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-size: 14px;
}
.kpi-value {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.01em;
  line-height: 1.3;
  color: var(--color-ink);
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
}

/* ── 检索配置：左参数 / 右开关 ── */
.cfg-two {
  display: grid;
  grid-template-columns: 1fr;
  gap: 14px;
}
@media (min-width: 560px) {
  .cfg-two {
    grid-template-columns: 1.3fr 1fr;
  }
}
.cfg-params {
  display: flex;
  flex-direction: column;
}
.cfg-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 7px 0;
  border-bottom: 1px solid var(--color-border-light);
  font-size: 13px;
}
.cfg-row:last-child {
  border-bottom: none;
}
.cfg-k {
  color: var(--color-ink-tertiary);
}
.cfg-v {
  color: var(--color-ink);
  font-weight: 500;
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
}
.cfg-toggles {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--color-surface-secondary);
}
.cfg-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--color-ink-secondary);
}
.toggle-pill {
  padding: 1px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
}
.toggle-pill.on {
  background: var(--color-success-bg);
  color: var(--color-success);
}
.toggle-pill.off {
  background: var(--color-surface);
  color: var(--color-ink-tertiary);
  border: 1px solid var(--color-border-light);
}

/* ── 入库配置：chips 式（仅 2 项，横排紧凑） ── */
.ingest-chips {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.ingest-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 6px 12px;
  border-radius: var(--radius-md);
  background: var(--color-surface-secondary);
  font-size: 12px;
}
.ic-k {
  color: var(--color-ink-tertiary);
}
.ingest-chip b {
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
  font-weight: 600;
  color: var(--color-ink);
}

/* ── 系统健康 / 模型（右栏 k/v 行） ── */
.kv-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--color-border-light);
  font-size: 13px;
}
.kv-row:last-child {
  border-bottom: none;
}
.kv-k {
  color: var(--color-ink-tertiary);
  flex-shrink: 0;
}
.kv-v {
  margin-left: auto;
  color: var(--color-ink);
  font-weight: 500;
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot.on {
  background: var(--color-success);
  box-shadow: 0 0 0 3px rgba(63, 191, 79, 0.14);
}
.dot.off {
  background: var(--color-danger);
  box-shadow: 0 0 0 3px rgba(249, 57, 32, 0.14);
}
.kv-glyph {
  color: var(--color-ink-tertiary);
  font-size: 14px;
  flex-shrink: 0;
}
.kv-glyph-text {
  width: 14px;
  font-size: 10px;
  font-weight: 600;
  text-align: center;
}

/* ── 页头运行状态徽章 ── */
.live-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 3px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  background: var(--color-danger-bg);
  color: var(--color-danger);
}
.live-pill.ok {
  background: var(--color-success-bg);
  color: var(--color-success);
}
.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.45;
  }
}

/* ── empty ── */
.console-empty {
  padding: 80px 20px;
}
.empty-hint {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.empty-hint code {
  font-family: var(--font-display);
}
</style>
