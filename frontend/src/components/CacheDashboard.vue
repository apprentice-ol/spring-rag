<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ReloadOutlined, ThunderboltOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import {
  getCacheStats, toggleCache, getSemanticRecords, deleteSemanticRecord, clearSemanticRecords,
  getRedisEntries, type CacheStats, type CacheLayerStat, type SemanticRecord, type RedisEntry,
} from '../api/cacheStats'
import { fmtTime, EMPTY } from '../utils/format'

/**
 * 缓存面板（手动刷新）：六层「仪表面板排」为签名元素——命中率大数字 + 状态色竖条 + 拨杆开关，
 * 看一眼就知道哪层在干活、哪层白干，当场可开关。下方三页签：键分布 / 语义记录（可删）/ Redis 键。
 * 命中计数为后端进程内自启动累计；开关为运行时状态（重启恢复配置文件）。
 */
const loading = ref(false)
const data = ref<CacheStats | null>(null)
const loadedAt = ref<number | null>(null)
const toggling = ref(false)

onMounted(() => load())

async function load() {
  loading.value = true
  try {
    data.value = await getCacheStats()
    loadedAt.value = Date.now()
  } catch {
    data.value = null
  } finally {
    loading.value = false
  }
}

const LAYER_LABEL: Record<string, string> = {
  intent: '意图分类',
  retrieval: '检索结果',
  logs: '日志工具',
  answer: '精确答案',
  embedding: '查询向量',
  semantic: '语义答案',
}

function pct(v: number | undefined): string {
  if (v == null) return EMPTY
  return (v * 100).toFixed(1) + '%'
}
function num(v: number | undefined | null): string {
  return v == null ? EMPTY : v.toLocaleString('en-US')
}
/** 命中率 → 信号色（token：success/signal/danger） */
function rateClass(l: CacheLayerStat): string {
  if (!l.enabled || !data.value?.enabled) return 'off'
  const total = l.hits + l.misses
  if (total === 0) return 'idle'
  if (l.hitRate >= 0.5) return 'good'
  if (l.hitRate >= 0.2) return 'fair'
  return 'poor'
}

async function onToggleLayer(layer: string | null, enabled: boolean) {
  toggling.value = true
  try {
    await toggleCache(layer, enabled)
    message.success(layer ? `${LAYER_LABEL[layer] ?? layer} 已${enabled ? '启用' : '停用'}` : `总开关已${enabled ? '开启' : '关闭'}`)
    await load()
  } catch {
    message.error('开关失败，重试或看后端日志')
    await load()
  } finally {
    toggling.value = false
  }
}

const circuitClass = computed(() => {
  const s = data.value?.circuit.state
  if (s === 'CLOSED') return 'good'
  if (s === 'HALF_OPEN') return 'fair'
  return 'poor'
})

const docverText = computed(() => {
  const d = data.value?.docver ?? {}
  return Object.entries(d).map(([k, v]) => `${k}=${v}`).join(' · ') || EMPTY
})

// ===== 语义记录页签 =====
const recLoading = ref(false)
const records = ref<SemanticRecord[]>([])
const recTotal = ref(0)
const recPage = ref(1)
const recSize = 10

async function loadRecords() {
  recLoading.value = true
  try {
    const p = await getSemanticRecords(recPage.value, recSize)
    records.value = p.records
    recTotal.value = p.total
  } catch {
    records.value = []
    recTotal.value = 0
  } finally {
    recLoading.value = false
  }
}

async function onTab(key: string) {
  if (key === 'records' && records.value.length === 0 && recTotal.value === 0) await loadRecords()
  if (key === 'redis' && redisEntries.value.length === 0) await loadRedisEntries()
}

async function removeRecord(id: number) {
  await deleteSemanticRecord(id)
  message.success('已删除')
  await Promise.all([loadRecords(), load()])
}

async function clearRecords() {
  await clearSemanticRecords()
  message.success('已清空语义缓存')
  recPage.value = 1
  await Promise.all([loadRecords(), load()])
}

async function onRecPage(p: number) {
  recPage.value = p
  await loadRecords()
}

const recColumns = [
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '答案预览', dataIndex: 'answer', key: 'answer', ellipsis: true },
  { title: '范式', dataIndex: 'paradigm', key: 'paradigm', width: 100 },
  { title: '命中', dataIndex: 'hitCount', key: 'hitCount', width: 76 },
  { title: '版本', dataIndex: 'docver', key: 'docver', width: 64 },
  { title: '更新时间', dataIndex: 'updateTime', key: 'updateTime', width: 150 },
  { title: '', key: 'op', width: 60 },
]

// ===== Redis 键页签 =====
const redisLayer = ref('retrieval')
const redisLoading = ref(false)
const redisEntries = ref<RedisEntry[]>([])
const REDIS_LAYER_OPTS = [
  { value: 'retrieval', label: '检索结果' },
  { value: 'answer', label: '精确答案' },
  { value: 'intent', label: '意图分类' },
  { value: 'embedding', label: '查询向量' },
  { value: 'logs', label: '日志工具' },
]

async function loadRedisEntries() {
  redisLoading.value = true
  try {
    redisEntries.value = await getRedisEntries(redisLayer.value, 100)
  } catch {
    redisEntries.value = []
  } finally {
    redisLoading.value = false
  }
}

async function onRedisLayerChange() {
  await loadRedisEntries()
}

function fmtTtl(s: number | null | undefined): string {
  if (s == null) return EMPTY
  if (s < 0) return '永续'
  if (s < 90) return `${s}s`
  if (s < 5400) return `${Math.round(s / 60)}m`
  if (s < 129600) return `${Math.round(s / 3600)}h`
  return `${Math.round(s / 86400)}d`
}

const redisColumns = [
  { title: '键（哈希）', dataIndex: 'key', key: 'key', width: 220 },
  { title: '剩余 TTL', dataIndex: 'ttlSeconds', key: 'ttlSeconds', width: 110 },
  { title: '答案预览', dataIndex: 'preview', key: 'preview', ellipsis: true },
]
</script>

<template>
  <div class="cache-page">
    <!-- 页头：eyebrow + 标题 + 总开关与刷新 -->
    <div class="page-header">
      <div class="page-header-text">
        <span class="eyebrow">Cache</span>
        <h2 class="page-title">缓存</h2>
        <span class="head-note">开关与命中率 · 运行时生效，重启恢复配置文件</span>
      </div>
      <div class="head-actions">
        <span v-if="loadedAt" class="num loaded-at">更新于 {{ fmtTime(loadedAt) }}</span>
        <span class="master-switch">
          <span class="master-label">总开关</span>
          <a-switch
            :checked="data?.enabled ?? false"
            :loading="toggling"
            size="small"
            @change="(v: any) => onToggleLayer(null, !!v)"
          />
        </span>
        <a-button size="small" :loading="loading" @click="load">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
      </div>
    </div>

    <a-spin :spinning="loading">
      <!-- 签名元素：六层仪表面板排 -->
      <div v-if="data" class="layer-strip" :class="{ allOff: !data.enabled }">
        <div
          v-for="l in data.layers"
          :key="l.layer"
          class="meter"
          :class="[rateClass(l), { off: !l.enabled || !data.enabled }]"
        >
          <span class="meter-bar" />
          <div class="meter-head">
            <span class="meter-key num">{{ l.layer }}</span>
            <a-switch
              size="small"
              :checked="l.enabled"
              :loading="toggling"
              @change="(v: any) => onToggleLayer(l.layer, !!v)"
            />
          </div>
          <div class="meter-name">{{ LAYER_LABEL[l.layer] ?? l.layer }}</div>
          <div class="meter-rate num">{{ pct(l.hitRate) }}</div>
          <div class="meter-gauge"><div class="meter-gauge-fill" :class="rateClass(l)" :style="{ width: (l.hitRate * 100) + '%' }" /></div>
          <div class="meter-counts num">{{ num(l.hits) }} / {{ num(l.misses) }}</div>
          <div class="meter-meta">
            <span>TTL {{ l.ttl ?? EMPTY }}</span>
            <span v-if="l.admissionThreshold != null && l.admissionThreshold > 1">准入 {{ l.admissionThreshold }}</span>
            <span v-if="l.windowHitRate != null">1h {{ pct(l.windowHitRate) }}（{{ num(l.windowHits ?? 0) }}/{{ num(l.windowMisses ?? 0) }}）</span>
          </div>
        </div>
      </div>

      <!-- 状态带：一行紧凑事实 -->
      <div v-if="data" class="status-strip">
        <span class="status-item">
          <span class="dot" :class="data.redisDown ? 'poor' : 'good'" />
          Redis {{ data.redisDown ? '不可用' : '正常' }}
        </span>
        <span class="status-sep">│</span>
        <span class="status-item">
          断路器 <span class="num" :class="circuitClass">{{ data.circuit.state }}</span>
          <span class="status-dim">（{{ data.circuit.failureThreshold }} 败熔断 · 冷却 {{ data.circuit.cooldown }}）</span>
        </span>
        <span class="status-sep">│</span>
        <span class="status-item">
          降级拒绝 <span class="num">{{ num(data.degrade.rejected) }}</span>
          <span class="status-dim">（降期限并发 {{ data.degrade.limit }}）</span>
        </span>
        <span class="status-sep">│</span>
        <span class="status-item">
          docver <span class="num">{{ docverText }}</span>
        </span>
      </div>

      <a-tabs v-if="data" default-active-key="keys" size="small" @change="onTab">
        <!-- 键分布 -->
        <a-tab-pane key="keys" tab="键分布">
          <div v-if="data.redisDown" class="empty-note">Redis 不可用，无法统计键分布</div>
          <div v-else class="kv-grid">
            <div v-for="(count, prefix) in data.keyCounts" :key="prefix" class="kv-item">
              <span class="kv-key num">{{ prefix }}</span>
              <span class="kv-val num">{{ num(count) }}</span>
            </div>
            <div v-if="Object.keys(data.keyCounts).length === 0" class="empty-note">暂无缓存键</div>
          </div>
        </a-tab-pane>

        <!-- 语义缓存记录 -->
        <a-tab-pane key="records" :tab="`语义记录${data.semantic?.rows != null ? ' · ' + num(data.semantic.rows) : ''}`">
          <div class="records-head">
            <span class="records-note">
              相似问题命中即重放缓存答案（阈值 {{ data.semantic?.threshold ?? EMPTY }}，累计命中
              <span class="num">{{ num(data.semantic?.totalHits) }}</span>）；删除记录后同义问题将重新生成
            </span>
            <a-popconfirm title="清空全部语义缓存记录？" @confirm="clearRecords">
              <a-button size="small" danger>
                <template #icon><DeleteOutlined /></template>
                清空
              </a-button>
            </a-popconfirm>
          </div>
          <a-table
            :data-source="records"
            :columns="recColumns"
            :loading="recLoading"
            :pagination="false"
            size="small"
            row-key="id"
            class="rec-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'question'">
                <span class="q-text" :title="record.question">{{ record.question }}</span>
              </template>
              <template v-else-if="column.key === 'answer'">
                <span class="a-text">{{ record.answer }}</span>
              </template>
              <template v-else-if="column.key === 'hitCount'">
                <span class="num">{{ record.hitCount }}</span>
              </template>
              <template v-else-if="column.key === 'docver'">
                <span class="num">{{ record.docver }}</span>
              </template>
              <template v-else-if="column.key === 'updateTime'">
                <span class="num">{{ record.updateTime ? fmtTime(record.updateTime) : EMPTY }}</span>
              </template>
              <template v-else-if="column.key === 'op'">
                <a-popconfirm title="删除这条缓存记录？" @confirm="removeRecord(record.id)">
                  <a-button type="text" size="small" danger>
                    <template #icon><DeleteOutlined /></template>
                  </a-button>
                </a-popconfirm>
              </template>
            </template>
          </a-table>
          <div v-if="recTotal > recSize" class="rec-pager">
            <a-pagination
              size="small"
              :current="recPage"
              :page-size="recSize"
              :total="recTotal"
              :show-total="(t: number) => `共 ${t} 条`"
              @change="onRecPage"
            />
          </div>
          <div v-if="!recLoading && records.length === 0" class="empty-note">
            还没有语义缓存记录——对话或评测跑几轮后自动生成
          </div>
        </a-tab-pane>

        <!-- Redis 键记录 -->
        <a-tab-pane key="redis" tab="Redis 记录">
          <div class="redis-head">
            <a-select v-model:value="redisLayer" size="small" style="width: 140px" :options="REDIS_LAYER_OPTS" @change="onRedisLayerChange" />
            <a-button size="small" :loading="redisLoading" @click="loadRedisEntries">
              <template #icon><ReloadOutlined /></template>
              重新加载
            </a-button>
            <span class="records-note">键为内容哈希（不可逆）；精确答案层附答案预览</span>
          </div>
          <a-table
            :data-source="redisEntries"
            :columns="redisColumns"
            :loading="redisLoading"
            :pagination="false"
            size="small"
            row-key="key"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'key'">
                <span class="num key-hash">{{ record.key }}</span>
              </template>
              <template v-else-if="column.key === 'ttlSeconds'">
                <span class="num">{{ fmtTtl(record.ttlSeconds) }}</span>
              </template>
              <template v-else-if="column.key === 'preview'">
                <span class="a-text">{{ record.preview ?? EMPTY }}</span>
              </template>
            </template>
          </a-table>
          <div v-if="!redisLoading && redisEntries.length === 0" class="empty-note">该层暂无缓存键</div>
        </a-tab-pane>
      </a-tabs>

      <div v-else-if="!loading" class="empty-note load-fail">
        <ThunderboltOutlined /> 加载失败或后端未启动，点右上角刷新重试
      </div>
    </a-spin>
  </div>
</template>

<style scoped>
.cache-page { padding: 4px 2px 12px; }

/* ── 页头 ── */
.page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; }
.page-header-text { display: flex; align-items: baseline; gap: 10px; }
.page-title { margin: 0; font-size: var(--text-xl); font-weight: 600; }
.head-note { font-size: 12px; color: var(--color-ink-tertiary); }
.head-actions { display: flex; align-items: center; gap: 12px; }
.loaded-at { font-size: 12px; color: var(--color-ink-tertiary); }
.master-switch { display: inline-flex; align-items: center; gap: 6px; }
.master-label { font-size: 12px; color: var(--color-ink-secondary); }

/* ── 签名：层仪表面板排 ── */
.layer-strip {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
@media (max-width: 1100px) { .layer-strip { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 640px) { .layer-strip { grid-template-columns: repeat(2, 1fr); } }

.meter {
  position: relative;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 12px 12px 10px 16px;
  overflow: hidden;
}
/* 左侧状态色竖条：设备指示灯 */
.meter-bar {
  position: absolute;
  left: 0; top: 0; bottom: 0;
  width: 3px;
}
.meter.good .meter-bar { background: var(--color-success); }
.meter.fair .meter-bar { background: var(--color-signal); }
.meter.poor .meter-bar { background: var(--color-danger); }
.meter.idle .meter-bar, .meter.off .meter-bar { background: var(--color-border); }

.meter.off { opacity: 0.55; }
.layer-strip.allOff .meter { opacity: 0.55; }

.meter-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2px; }
.meter-key { font-size: 11px; letter-spacing: 0.06em; color: var(--color-ink-tertiary); text-transform: uppercase; }
.meter-name { font-size: 12px; color: var(--color-ink-secondary); margin-bottom: 6px; }
.meter-rate { font-size: 24px; font-weight: 600; line-height: 1.1; letter-spacing: -0.01em; }
.meter.idle .meter-rate, .meter.off .meter-rate { color: var(--color-ink-tertiary); }

.meter-gauge { height: 4px; border-radius: 2px; background: var(--color-surface-secondary); margin: 8px 0 6px; overflow: hidden; }
.meter-gauge-fill { height: 100%; border-radius: 2px; }
.meter-gauge-fill.good { background: var(--color-success); }
.meter-gauge-fill.fair { background: var(--color-signal); }
.meter-gauge-fill.poor { background: var(--color-danger); }
.meter-gauge-fill.idle, .meter-gauge-fill.off { background: var(--color-border); }

.meter-counts { font-size: 11px; color: var(--color-ink-tertiary); }
.meter-meta { display: flex; flex-wrap: wrap; gap: 8px; font-size: 11px; color: var(--color-ink-tertiary); margin-top: 4px; }

/* ── 状态带 ── */
.status-strip {
  display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
  font-size: 12px; color: var(--color-ink-secondary);
  padding: 8px 12px; margin-bottom: 12px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
}
.status-item { display: inline-flex; align-items: center; gap: 5px; }
.status-sep { color: var(--color-border); }
.status-dim { color: var(--color-ink-tertiary); }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; }
.dot.good { background: var(--color-success); }
.dot.poor { background: var(--color-danger); }
.num.good { color: var(--color-success); }
.num.fair { color: var(--color-signal); }
.num.poor { color: var(--color-danger); }

/* ── 键分布 ── */
.kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 8px; }
.kv-item {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 8px 10px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
}
.kv-key { font-size: 11px; color: var(--color-ink-secondary); }
.kv-val { font-size: 14px; font-weight: 600; }

/* ── 记录页签 ── */
.records-head, .redis-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.records-head { justify-content: space-between; }
.records-note { font-size: 12px; color: var(--color-ink-tertiary); }
.rec-table { margin-top: 2px; }
.q-text { font-weight: 500; }
.a-text { color: var(--color-ink-secondary); font-size: 12px; }
.key-hash { font-size: 12px; color: var(--color-ink-secondary); }
.rec-pager { display: flex; justify-content: flex-end; margin-top: 10px; }

.empty-note { font-size: 12px; color: var(--color-ink-tertiary); padding: 10px 0; }
.load-fail { padding: 48px 0; text-align: center; }
</style>
