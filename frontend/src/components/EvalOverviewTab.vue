<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { listRuns, type EvalRun } from '../api/eval'
import { parseAggregate, fmtScore, statusText, isRetrievalMetric, metricLabel, paradigmLabel } from './evalShared'

const props = defineProps<{ datasets: { id: number; name: string; itemCount?: number }[] }>()

const selectedDatasetId = ref<number | null>(null)
const runs = ref<EvalRun[]>([])
const loading = ref(false)

onMounted(() => {
  if (props.datasets.length) selectedDatasetId.value = props.datasets[0].id
})

watch(
  () => props.datasets,
  (ds) => {
    if (ds.length && !selectedDatasetId.value) selectedDatasetId.value = ds[0].id
  },
)

watch(selectedDatasetId, async (id) => {
  if (!id) {
    runs.value = []
    return
  }
  loading.value = true
  try {
    runs.value = await listRuns({ datasetId: id })
  } finally {
    loading.value = false
  }
})

// 该数据集 DONE 的 run，按 id 倒序（越新越前）
const doneRuns = computed(() =>
  runs.value.filter((r) => r.status === 'DONE').sort((a, b) => b.id - a.id),
)
const latest = computed(() => doneRuns.value[0] || null)
const prev = computed(() => doneRuns.value[1] || null)
const latestAgg = computed(() => parseAggregate(latest.value))

const KPIS = ['recall_at_10', 'mrr', 'precision_at_10', 'ndcg_at_10']

interface KpiCard {
  key: string
  label: string
  value: number | null
  delta: number | null
  retrieval: boolean
}
const kpiCards = computed<KpiCard[]>(() => {
  const agg = latestAgg.value
  const prevAgg = parseAggregate(prev.value)
  return KPIS.map((k) => {
    const cur = agg?.[k]?.mean ?? null
    const pv = prevAgg?.[k]?.mean ?? null
    return {
      key: k,
      label: metricLabel(k),
      value: cur,
      delta: cur != null && pv != null ? cur - pv : null,
      retrieval: isRetrievalMetric(k),
    }
  })
})

function durMin(run: EvalRun | null): string {
  if (!run?.startedAt || !run?.finishedAt) return '-'
  const a = new Date(run.startedAt).getTime()
  const b = new Date(run.finishedAt).getTime()
  if (!Number.isFinite(a) || !Number.isFinite(b) || b < a) return '-'
  return ((b - a) / 60000).toFixed(1) + ' min'
}

// 趋势条:最近 8 次完成运行,按当前选中的 KPI 指标画水平条(纯 CSS,不引图表库)
const RECENT_LIMIT = 8
/** 当前选中的趋势指标,点击 KPI 卡切换 */
const trendMetric = ref<(typeof KPIS)[number]>('recall_at_10')
const recentRuns = computed(() => doneRuns.value.slice(0, RECENT_LIMIT))
const trendMax = computed(() => {
  const vals = recentRuns.value
    .map((r) => parseAggregate(r)?.[trendMetric.value]?.mean)
    .filter((v): v is number => v != null)
  return vals.length ? Math.max(...vals) : 0
})
function runScore(r: EvalRun): number | null {
  return parseAggregate(r)?.[trendMetric.value]?.mean ?? null
}
function fmtDate(t: string | undefined | null): string {
  return (t || '—').replace('T', ' ').slice(5, 16)
}
</script>

<template>
  <div class="overview">
    <!-- 筛选卡:数据集选择 -->
    <div class="filter-card">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">数据集</span>
          <a-select v-model:value="selectedDatasetId" placeholder="选择数据集" style="width: 260px">
            <a-select-option v-for="d in datasets" :key="d.id" :value="d.id">
              {{ d.name }}（{{ d.itemCount ?? 0 }} 题）
            </a-select-option>
          </a-select>
        </div>
        <span class="ds-hint">所有指标均限定在所选数据集下（不同数据集难度不同，分数不可跨集比较）</span>
      </div>
    </div>

    <a-spin :spinning="loading">
      <a-empty v-if="!selectedDatasetId" description="请选择数据集" />
      <a-empty v-else-if="!latest" description="该数据集暂无已完成的运行" />
      <template v-else>
        <!-- KPI 卡片:大等宽数字 + 阶段徽标 + 环比;点击切换下方趋势指标 -->
        <div class="kpi-grid">
          <div
            v-for="k in kpiCards"
            :key="k.key"
            class="kpi-card"
            :class="{ active: trendMetric === k.key }"
            role="button"
            :title="'查看 ' + k.label + ' 的近期走势'"
            @click="trendMetric = k.key"
          >
            <div class="kpi-head">
              <span class="kpi-label">{{ k.label }}</span>
              <span class="kpi-stage" :class="k.retrieval ? 'stage-ret' : 'stage-sort'">
                {{ k.retrieval ? '检索' : '精排' }}
              </span>
            </div>
            <div class="kpi-value">{{ k.value != null ? fmtScore(k.value) : '—' }}</div>
            <div class="kpi-delta">
              <template v-if="k.delta != null">
                <span :class="k.delta >= 0 ? 'up' : 'down'">{{ k.delta >= 0 ? '↑' : '↓' }} {{ fmtScore(Math.abs(k.delta)) }}</span>
                <span class="vs">vs 上次</span>
              </template>
              <template v-else-if="k.value != null"><span class="vs">首次运行</span></template>
            </div>
          </div>
        </div>

        <!-- 运行历史:最近 N 次按选中指标画水平条,最新一条高亮 -->
        <div class="trend-card">
          <div class="trend-head">
            <span class="trend-title">近期 {{ metricLabel(trendMetric) }} 走势</span>
            <span class="trend-sub">共 {{ doneRuns.length }} 次完成运行{{ doneRuns.length > RECENT_LIMIT ? `，显示最近 ${RECENT_LIMIT} 次` : '' }} · 点击上方卡片切换指标</span>
          </div>
          <div class="trend-rows">
            <div
              v-for="r in recentRuns"
              :key="r.id"
              class="trend-row"
              :class="{ latest: r.id === latest.id }"
            >
              <span class="tr-id">#{{ r.id }}</span>
              <a-tag v-if="r.paradigm" class="tr-paradigm" color="purple">{{ paradigmLabel(r.paradigm) }}</a-tag>
              <div class="tr-bar-track">
                <div
                  class="tr-bar"
                  :class="{ dim: r.id !== latest.id }"
                  :style="{ width: ((runScore(r) ?? 0) / (trendMax || 1)) * 100 + '%' }"
                />
              </div>
              <span class="tr-score">{{ runScore(r) != null ? fmtScore(runScore(r)!) : '—' }}</span>
              <span class="tr-dur">{{ durMin(r) }}</span>
              <span class="tr-time">{{ fmtDate(r.finishedAt) }}</span>
            </div>
          </div>
        </div>
      </template>
    </a-spin>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ds-hint {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

/* ── KPI 卡 ── */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
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
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.kpi-card:hover {
  border-color: var(--color-primary);
}
/* 选中态:当前趋势指标对应的 KPI 卡 */
.kpi-card.active {
  border-color: var(--color-primary);
  background: var(--color-primary-light);
  box-shadow: 0 0 0 1px var(--color-primary) inset;
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
/* 阶段徽标:检索=蓝、精排=琥珀(浅底小 tag,代替原彩色顶边) */
.kpi-stage {
  font-size: 11px;
  line-height: 1;
  padding: 3px 6px;
  border-radius: var(--radius-sm);
  font-weight: 500;
}
.kpi-stage.stage-ret {
  color: var(--color-primary);
  background: var(--color-primary-light);
}
.kpi-stage.stage-sort {
  color: #b07810;
  background: var(--color-signal-bg);
}
.kpi-value {
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--color-ink);
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
  line-height: 1.3;
}
.kpi-delta {
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 18px;
}
.kpi-delta .up {
  color: var(--color-success);
  font-weight: 500;
  font-family: var(--font-display);
}
.kpi-delta .down {
  color: var(--color-danger);
  font-weight: 500;
  font-family: var(--font-display);
}
.kpi-delta .vs {
  color: var(--color-ink-tertiary);
}

/* ── 运行历史(主指标水平条) ── */
.trend-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  padding: 14px 20px 16px;
}
.trend-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}
.trend-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.trend-sub {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.trend-rows {
  display: flex;
  flex-direction: column;
}
.trend-row {
  display: grid;
  grid-template-columns: 48px 84px 1fr 64px 72px 100px;
  align-items: center;
  gap: 12px;
  padding: 9px 10px;
  border-radius: var(--radius-md);
  font-size: 12px;
}
.trend-row:hover {
  background: #f7f9fb;
}
.trend-row.latest {
  background: var(--color-primary-light);
}
.tr-id {
  font-family: var(--font-display);
  color: var(--color-ink-secondary);
}
.trend-row.latest .tr-id {
  color: var(--color-primary);
  font-weight: 600;
}
.tr-paradigm {
  margin-right: 0 !important;
  font-size: 11px;
  line-height: 16px;
  justify-self: start;
}
.tr-bar-track {
  height: 16px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-secondary);
  overflow: hidden;
}
.tr-bar {
  height: 100%;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  min-width: 4px;
  transition: width 0.25s ease;
}
.tr-bar.dim {
  opacity: 0.35;
}
.tr-score {
  font-family: var(--font-display);
  font-feature-settings: 'tnum';
  color: var(--color-ink);
  text-align: right;
}
.tr-dur {
  color: var(--color-ink-tertiary);
  font-family: var(--font-display);
  text-align: right;
}
.tr-time {
  color: var(--color-ink-tertiary);
  font-family: var(--font-display);
  text-align: right;
}

@media (max-width: 768px) {
  .trend-row {
    grid-template-columns: 44px 1fr 52px;
  }
  .tr-bar-track, .tr-dur, .tr-time {
    display: none;
  }
}
</style>
