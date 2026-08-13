<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ReloadOutlined, EyeOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { listRuns, retryRun, type EvalRun } from '../api/eval'
import { parseAggregate, fmtScore, statusText, paradigmLabel } from './evalShared'

const props = defineProps<{ datasets: { id: number; name: string }[] }>()

const runs = ref<EvalRun[]>([])
const loading = ref(false)
const filterDataset = ref<number | undefined>(undefined)
const filterStatus = ref<string | undefined>(undefined)

let pollTimer: number | null = null

const datasetNameMap = computed(() => {
  const m = new Map<number, string>()
  for (const d of props.datasets) m.set(d.id, d.name)
  return m
})

async function load() {
  loading.value = true
  try {
    runs.value = await listRuns({ datasetId: filterDataset.value, status: filterStatus.value })
  } finally {
    loading.value = false
  }
  schedulePoll()
}

function schedulePoll() {
  const hasRunning = runs.value.some((r) => r.status === 'RUNNING' || r.status === 'PENDING')
  if (hasRunning && !pollTimer) {
    pollTimer = window.setInterval(async () => {
      runs.value = await listRuns({ datasetId: filterDataset.value, status: filterStatus.value })
      if (!runs.value.some((r) => r.status === 'RUNNING' || r.status === 'PENDING') && pollTimer) {
        clearInterval(pollTimer)
        pollTimer = null
      }
    }, 2000)
  } else if (!hasRunning && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(load)
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})

function openDetail(id: number) {
  // 跳转到运行详情独立页（hash 子路由 #/admin/eval/runs/{id}）
  window.location.hash = '#/admin/eval/runs/' + id
}

async function retry(run: EvalRun) {
  try {
    const { runId } = await retryRun(run.id)
    message.success(`已重新触发运行 #${runId}`)
    await load()
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '重试失败')
  }
}

interface ErrResp {
  response?: { data?: { message?: string } }
}

/** 关键指标摘要：MRR + Recall@10 的 mean */
function keyMetrics(run: EvalRun): string {
  const agg = parseAggregate(run)
  if (!agg) return '—'
  const pick = (k: string) => (agg[k] ? fmtScore(agg[k].mean) : '-')
  return `MRR ${pick('mrr')} · R@10 ${pick('recall_at_10')}`
}

const tableData = computed(() =>
  runs.value.map((r) => ({
    ...r,
    datasetName: datasetNameMap.value.get(r.datasetId) || '#' + r.datasetId,
    keyMetrics: keyMetrics(r),
  })),
)

const columns = [
  { title: '运行', dataIndex: 'id', key: 'id', width: 80 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
  { title: '数据集', dataIndex: 'datasetName', key: 'datasetName', width: 150, ellipsis: true },
  { title: '范式', dataIndex: 'paradigm', key: 'paradigm', width: 110 },
  { title: '进度', dataIndex: 'done', key: 'done', width: 90 },
  { title: '关键指标', dataIndex: 'keyMetrics', key: 'keyMetrics', ellipsis: true },
  { title: '时间', dataIndex: 'finishedAt', key: 'finishedAt', width: 160 },
  { title: '操作', key: 'action', width: 100, fixed: 'right' as const },
]

const STATUS_COLOR: Record<string, string> = {
  DONE: 'green',
  RUNNING: 'processing',
  PENDING: 'orange',
  FAILED: 'red',
}
</script>

<template>
  <div class="eval-run-tab">
    <div class="filter-bar">
      <a-select
        v-model:value="filterDataset"
        placeholder="按数据集筛选"
        allow-clear
        style="width: 200px"
        @change="load"
      >
        <a-select-option v-for="d in datasets" :key="d.id" :value="d.id">{{ d.name }}</a-select-option>
      </a-select>
      <a-select
        v-model:value="filterStatus"
        placeholder="按状态筛选"
        allow-clear
        style="width: 140px"
        @change="load"
      >
        <a-select-option value="DONE">完成</a-select-option>
        <a-select-option value="RUNNING">运行中</a-select-option>
        <a-select-option value="FAILED">失败</a-select-option>
      </a-select>
      <a-button @click="load">
        <template #icon><ReloadOutlined /></template>刷新
      </a-button>
    </div>

    <a-table
      :data-source="tableData"
      :columns="columns"
      :loading="loading"
      :pagination="{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['10', '20', '50'], showTotal: (t: number) => `共 ${t} 条` }"
      size="middle"
      row-key="id"
      :scroll="{ x: 1010 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'id'">
          <a class="run-id" @click="openDetail(record.id)">#{{ record.id }}</a>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="STATUS_COLOR[record.status] || 'default'">{{ statusText(record.status) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'paradigm'">
          <a-tag v-if="record.paradigm" color="teal">{{ paradigmLabel(record.paradigm) }}</a-tag>
          <span v-else class="muted">-</span>
        </template>
        <template v-else-if="column.key === 'done'">{{ record.done }}/{{ record.total ?? '-' }}</template>
        <template v-else-if="column.key === 'finishedAt'">{{ record.finishedAt || record.startedAt || '—' }}</template>
        <template v-else-if="column.key === 'action'">
          <a-button type="link" size="small" @click="openDetail(record.id)">
            <template #icon><EyeOutlined /></template>详情
          </a-button>
          <a-button v-if="record.status === 'FAILED'" type="link" size="small" @click="retry(record)">
            <template #icon><ReloadOutlined /></template>重试
          </a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>

<style scoped>
.eval-run-tab {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.filter-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.run-id {
  color: var(--color-primary);
  font-weight: 600;
  cursor: pointer;
}
.run-id:hover {
  text-decoration: underline;
}
.muted {
  color: #d9d9d9;
}
</style>
