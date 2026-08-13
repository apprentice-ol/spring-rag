<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { listRuns, type EvalRun } from '../api/eval'
import { parseAggregate, fmtScore, statusText, scoreTone, isRetrievalMetric, metricLabel, paradigmLabel } from './evalShared'

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
</script>

<template>
  <div class="overview">
    <div class="ds-select-row">
      <span class="label">数据集</span>
      <a-select v-model:value="selectedDatasetId" placeholder="选择数据集" style="width: 260px">
        <a-select-option v-for="d in datasets" :key="d.id" :value="d.id">
          {{ d.name }}（{{ d.itemCount ?? 0 }} 题）
        </a-select-option>
      </a-select>
      <span class="sub">所有指标均限定在所选数据集下（不同数据集难度不同，分数不可跨集比较）</span>
    </div>

    <a-spin :spinning="loading">
      <a-empty v-if="!selectedDatasetId" description="请选择数据集" />
      <a-empty v-else-if="!latest" description="该数据集暂无已完成的运行" />
      <template v-else>
        <!-- KPI 卡片 -->
        <div class="kpi-grid">
          <div
            v-for="k in kpiCards"
            :key="k.key"
            class="kpi-card"
            :class="k.value != null ? ['tone-' + scoreTone(k.value), isRetrievalMetric(k.key) ? 'stage-ret' : 'stage-sort'] : ''"
          >
            <div class="kpi-label">{{ k.label }}</div>
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

        <!-- 最近运行摘要 -->
        <div class="latest-bar">
          <span>最近运行 <b>#{{ latest.id }}</b></span>
          <a-tag :color="latest.status === 'DONE' ? 'green' : 'processing'">{{ statusText(latest.status) }}</a-tag>
          <a-tag v-if="latest.paradigm" color="teal">{{ paradigmLabel(latest.paradigm) }}</a-tag>
          <span>题数 {{ latest.done }}/{{ latest.total ?? '-' }}</span>
          <span>耗时 {{ durMin(latest) }}</span>
          <span>完成 {{ latest.finishedAt || '—' }}</span>
        </div>

        <!-- 趋势占位 -->
        <div class="trend-placeholder">
          <div class="tp-title">指标趋势</div>
          <div class="tp-body">
            <span>该数据集已完成 <b>{{ doneRuns.length }}</b> 次运行</span>
            <span class="hint">运行 ≥ 3 次后展示趋势折线图（第一版暂未启用）</span>
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
  gap: 16px;
}
.ds-select-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ds-select-row .label {
  font-size: 13px;
  color: var(--color-ink-secondary);
}
.ds-select-row .sub {
  font-size: 11px;
  color: var(--color-ink-tertiary);
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.kpi-card {
  background: #ffffff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 14px 16px;
  border-top: 3px solid #d9d9d9;
}
.kpi-card.stage-ret {
  border-top-color: #1890ff;
}
.kpi-card.stage-sort {
  border-top-color: #fa8c16;
}
.kpi-label {
  font-size: 13px;
  color: #666;
  font-weight: 500;
}
.kpi-value {
  font-size: 28px;
  font-weight: 700;
  color: #333;
  margin: 4px 0;
}
.kpi-card.tone-good .kpi-value {
  color: #52c41a;
}
.kpi-card.tone-mid .kpi-value {
  color: #faad14;
}
.kpi-card.tone-bad .kpi-value {
  color: #f5222d;
}
.kpi-delta {
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 4px;
}
.kpi-delta .up {
  color: #52c41a;
  font-weight: 600;
}
.kpi-delta .down {
  color: #f5222d;
  font-weight: 600;
}
.kpi-delta .vs {
  color: var(--color-ink-tertiary);
}

.latest-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--color-ink-secondary);
  padding: 10px 14px;
  background: var(--color-surface-secondary);
  border-radius: var(--radius-sm);
}

.trend-placeholder {
  border: 1px dashed var(--color-border-light);
  border-radius: var(--radius-sm);
  padding: 18px;
  text-align: center;
}
.tp-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink-secondary);
  margin-bottom: 6px;
}
.tp-body {
  font-size: 13px;
  color: var(--color-ink-tertiary);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tp-body .hint {
  font-size: 11px;
}
</style>
