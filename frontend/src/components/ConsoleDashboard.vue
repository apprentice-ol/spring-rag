<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import {
  ReloadOutlined,
  ArrowRightOutlined,
  FileTextOutlined,
  DatabaseOutlined,
  MessageOutlined,
  BarChartOutlined,
  ThunderboltOutlined,
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

// 核心指标 KPI（仿 ragent：彩色图标方块）
const stats = computed(() => {
  const s = data.value?.stats
  if (!s) return []
  return [
    { label: '文档', value: statVal(s.documents), icon: FileTextOutlined, bg: '#DBEAFE', fg: '#2563EB' },
    { label: '向量块', value: statVal(s.chunks), icon: DatabaseOutlined, bg: '#D1FAE5', fg: '#0f766e' },
    { label: '会话', value: statVal(s.conversations), icon: MessageOutlined, bg: '#FEF3C7', fg: '#D97706' },
    { label: '评测运行', value: statVal(s.evalRuns), icon: BarChartOutlined, bg: '#EDE9FE', fg: '#7C3AED' },
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
  <div class="rg">
    <!-- 顶栏：大标题 + 操作区（仿 ragent DashboardHeader） -->
    <header class="rg-header">
      <h1 class="rg-title">控制台</h1>
      <div class="rg-header-right">
        <span v-if="data" class="rg-live" :class="{ ok: allOk }">
          <span class="rg-live-dot"></span>{{ allOk ? '运行正常' : '存在异常' }}
        </span>
        <button class="rg-icon-btn" :class="{ spinning: loading }" title="刷新" @click="load">
          <ReloadOutlined />
        </button>
      </div>
    </header>

    <div v-if="data" class="rg-grid">
      <!-- 左主区 -->
      <div class="rg-main">
        <!-- 核心指标 -->
        <section class="rg-card">
          <h3 class="rg-card-title">核心指标</h3>
          <div class="rg-kpis">
            <div v-for="s in stats" :key="s.label" class="rg-kpi">
              <div class="rg-kpi-text">
                <div class="rg-kpi-num">{{ s.value }}</div>
                <div class="rg-kpi-label">{{ s.label }}</div>
              </div>
              <div class="rg-kpi-icon" :style="{ background: s.bg, color: s.fg }">
                <component :is="s.icon" />
              </div>
            </div>
          </div>
        </section>

        <!-- 检索配置 -->
        <section class="rg-card">
          <h3 class="rg-card-title">
            <ApiOutlined class="rg-title-icon" />检索配置
            <a class="rg-action" @click="go('#/eval')">评测看板<ArrowRightOutlined /></a>
          </h3>
          <div class="rg-two">
            <div class="rg-params">
              <div v-for="p in retrievalParams" :key="p.k" class="rg-param">
                <span class="rg-param-k">{{ p.k }}</span>
                <span class="rg-param-v">{{ p.v }}</span>
              </div>
            </div>
            <div class="rg-toggles">
              <div v-for="t in toggles" :key="t.k" class="rg-toggle">
                <span>{{ t.k }}</span>
                <span class="rg-pill" :class="t.on ? 'on' : 'off'">{{ t.on ? '已启用' : '关闭' }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- 入库配置 -->
        <section class="rg-card">
          <h3 class="rg-card-title"><DatabaseOutlined class="rg-title-icon" />入库配置</h3>
          <div class="rg-params rg-params-2">
            <div v-for="r in chunkRows" :key="r.k" class="rg-param">
              <span class="rg-param-k">{{ r.k }}</span>
              <span class="rg-param-v">{{ r.v }}</span>
            </div>
          </div>
        </section>
      </div>

      <!-- 右侧 sticky 边栏 -->
      <aside class="rg-side">
        <!-- 系统健康 -->
        <section class="rg-card">
          <h3 class="rg-card-title"><ThunderboltOutlined class="rg-title-icon" />系统健康</h3>
          <div class="rg-health">
            <div class="rg-health-row">
              <span class="rg-dot" :class="isOnline(data.system.backend) ? 'on' : 'off'"></span>
              <span class="rg-health-k">后端服务</span>
              <span class="rg-health-v">{{ isOnline(data.system.backend) ? 'Online' : 'Offline' }}</span>
            </div>
            <div class="rg-health-row">
              <span class="rg-dot" :class="isOnline(data.system.database) ? 'on' : 'off'"></span>
              <span class="rg-health-k">PostgreSQL</span>
              <span class="rg-health-v">{{ isOnline(data.system.database) ? 'Connected' : 'Disconnected' }}</span>
            </div>
            <div class="rg-health-row">
              <CloudServerOutlined class="rg-health-glyph" />
              <span class="rg-health-k">运行环境</span>
              <span class="rg-health-v">JDK {{ data.system.javaVersion }}</span>
            </div>
            <div class="rg-health-row">
              <ThunderboltOutlined class="rg-health-glyph" />
              <span class="rg-health-k">应用</span>
              <span class="rg-health-v">{{ data.system.appName || '—' }}</span>
            </div>
          </div>
        </section>

        <!-- 模型 -->
        <section class="rg-card">
          <h3 class="rg-card-title"><CloudServerOutlined class="rg-title-icon" />模型</h3>
          <div class="rg-models">
            <div v-for="r in modelRows" :key="r.k" class="rg-model">
              <span class="rg-model-k">{{ r.k }}</span>
              <span class="rg-model-v">{{ r.v }}</span>
            </div>
          </div>
        </section>

        <!-- 快捷入口 -->
        <section class="rg-card">
          <h3 class="rg-card-title">快捷入口</h3>
          <div class="rg-entry" @click="go('#/')">
            <MessageOutlined class="rg-entry-icon" />
            <div class="rg-entry-text">
              <div class="rg-entry-name">对话 / 入库</div>
              <div class="rg-entry-hint">回到工作台</div>
            </div>
            <ArrowRightOutlined class="rg-entry-go" />
          </div>
          <div class="rg-entry" @click="go('#/eval')">
            <BarChartOutlined class="rg-entry-icon" />
            <div class="rg-entry-text">
              <div class="rg-entry-name">评测看板</div>
              <div class="rg-entry-hint">检索指标 · 黄金集</div>
            </div>
            <ArrowRightOutlined class="rg-entry-go" />
          </div>
        </section>
      </aside>
    </div>

    <div v-else-if="!loading" class="rg-empty">
      <CloseCircleFilled style="font-size: 32px; color: #cbd5e1" />
      <p>无法获取控制台数据</p>
      <span>后端 <code>/console/overview</code> 不可达</span>
    </div>
  </div>
</template>

<style scoped>
/* 仿 ragent DashboardPage：大标题 + 左右分栏 + DashCard + 彩色 KPI；slate 中性 + teal 品牌 */
.rg {
  --slate-900: #0f172a;
  --slate-800: #1e293b;
  --slate-700: #334155;
  --slate-600: #475569;
  --slate-500: #64748b;
  --slate-400: #94a3b8;
  --slate-200: #e2e8f0;
  --slate-100: #f1f5f9;
  --slate-50: #f8fafc;
  --teal: #0f766e;
  --teal-soft: #d1fae5;

  padding: 24px 28px 48px;
  max-width: 1200px;
  margin: 0 auto;
  background: #fafafa;
  min-height: 100%;
  color: var(--slate-700);
  font-feature-settings: 'tnum';
}

/* 顶栏 */
.rg-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.rg-title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--slate-900);
}
.rg-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.rg-live {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  background: #fef2f2;
  color: #dc2626;
}
.rg-live.ok {
  background: var(--teal-soft);
  color: var(--teal);
}
.rg-live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.45;
  }
}
.rg-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid var(--slate-200);
  background: #fff;
  color: var(--slate-500);
  cursor: pointer;
  font-size: 15px;
  transition: color 0.15s, border-color 0.15s;
}
.rg-icon-btn:hover {
  color: var(--slate-700);
  border-color: var(--slate-400);
}
.rg-icon-btn.spinning :deep(svg) {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 左右分栏 */
.rg-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 20px;
}
@media (min-width: 1080px) {
  .rg-grid {
    grid-template-columns: 1fr 320px;
  }
  .rg-side {
    position: sticky;
    top: 20px;
    align-self: start;
  }
}
.rg-main,
.rg-side {
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-width: 0;
}

/* DashCard */
.rg-card {
  background: #fff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.rg-card-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 600;
  color: var(--slate-700);
}
.rg-title-icon {
  color: var(--teal);
  font-size: 15px;
}
.rg-action {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--teal);
  cursor: pointer;
}
.rg-action:hover {
  text-decoration: underline;
}

/* 核心 KPI */
.rg-kpis {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 14px;
}
@media (min-width: 620px) {
  .rg-kpis {
    grid-template-columns: repeat(4, 1fr);
  }
}
.rg-kpi {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 16px;
  border-radius: 12px;
  background: var(--slate-50);
}
.rg-kpi-num {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--slate-900);
  font-family: var(--font-display);
  line-height: 1.15;
}
.rg-kpi-label {
  margin-top: 4px;
  font-size: 13px;
  color: var(--slate-500);
}
.rg-kpi-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  font-size: 18px;
}

/* 两列（参数 + 开关） */
.rg-two {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}
@media (min-width: 560px) {
  .rg-two {
    grid-template-columns: 1.3fr 1fr;
  }
}
.rg-params {
  display: flex;
  flex-direction: column;
}
.rg-params-2 {
  display: grid;
  grid-template-columns: repeat(2, max-content);
  gap: 8px 32px;
}
.rg-param {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--slate-100);
  font-size: 13px;
}
.rg-param:last-child {
  border-bottom: none;
}
.rg-params-2 .rg-param {
  border-bottom: none;
  padding: 6px 0;
  gap: 16px;
}
.rg-param-k {
  color: var(--slate-500);
}
.rg-param-v {
  color: var(--slate-800);
  font-weight: 600;
  font-family: var(--font-display);
}

.rg-toggles {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-radius: 12px;
  background: var(--slate-50);
}
.rg-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--slate-600);
}
.rg-pill {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  font-family: var(--font-display);
}
.rg-pill.on {
  background: var(--teal-soft);
  color: var(--teal);
}
.rg-pill.off {
  background: var(--slate-100);
  color: var(--slate-400);
}

/* 系统健康（右栏） */
.rg-health {
  display: flex;
  flex-direction: column;
}
.rg-health-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid var(--slate-100);
  font-size: 13px;
}
.rg-health-row:last-child {
  border-bottom: none;
}
.rg-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
}
.rg-dot.on {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.14);
}
.rg-dot.off {
  background: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.14);
}
.rg-health-glyph {
  color: var(--slate-400);
  font-size: 14px;
}
.rg-health-k {
  color: var(--slate-500);
}
.rg-health-v {
  margin-left: auto;
  color: var(--slate-800);
  font-weight: 600;
  font-family: var(--font-display);
  font-size: 12.5px;
}

/* 模型（右栏） */
.rg-models {
  display: flex;
  flex-direction: column;
}
.rg-model {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 0;
  border-bottom: 1px solid var(--slate-100);
  font-size: 13px;
}
.rg-model:last-child {
  border-bottom: none;
}
.rg-model-k {
  color: var(--slate-500);
}
.rg-model-v {
  color: var(--slate-800);
  font-weight: 600;
  font-family: var(--font-display);
  font-size: 12.5px;
}

/* 快捷入口（右栏） */
.rg-entry {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s;
}
.rg-entry + .rg-entry {
  margin-top: 4px;
}
.rg-entry:hover {
  background: var(--slate-50);
}
.rg-entry-icon {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--teal-soft);
  color: var(--teal);
  font-size: 16px;
}
.rg-entry-text {
  flex: 1;
  min-width: 0;
}
.rg-entry-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--slate-800);
}
.rg-entry-hint {
  font-size: 12px;
  color: var(--slate-400);
}
.rg-entry-go {
  color: var(--slate-400);
  font-size: 12px;
}

/* empty */
.rg-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 80px 20px;
  color: var(--slate-400);
  text-align: center;
}
.rg-empty p {
  margin: 6px 0 0;
  font-size: 14px;
  color: var(--slate-600);
  font-weight: 500;
}
.rg-empty code {
  font-family: var(--font-display);
  font-size: 12px;
}

/* 响应式：窄屏 KPI 变 2 列 */
@media (max-width: 540px) {
  .rg {
    padding: 18px 14px 36px;
  }
  .rg-title {
    font-size: 24px;
  }
}
</style>
