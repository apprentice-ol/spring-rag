<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { listDatasets, type EvalDataset } from '../api/eval'
import EvalOverviewTab from './EvalOverviewTab.vue'
import EvalDatasetTab from './EvalDatasetTab.vue'
import EvalRunTab from './EvalRunTab.vue'
import EvalRunDetail from './EvalRunDetail.vue'

const activeTab = ref('overview')
const datasets = ref<EvalDataset[]>([])

/** 运行记录 tab 的 reload 句柄：tab 面板保活（首次激活后不重新挂载），
 * 别的 tab 触发的新 run 靠这里在切回时刷新，否则列表发现不了 RUNNING 任务、轮询永远不起 */
const runTabRef = ref<InstanceType<typeof EvalRunTab> | null>(null)
watch(activeTab, (tab) => {
  if (tab === 'runs') {
    runTabRef.value?.reload()
  }
})

const currentHash = ref(window.location.hash)
function onHashChange() {
  currentHash.value = window.location.hash
}

async function loadDatasets() {
  try {
    datasets.value = await listDatasets()
  } catch {
    /* ignore */
  }
}

onMounted(() => {
  loadDatasets()
  window.addEventListener('hashchange', onHashChange)
})
onUnmounted(() => {
  window.removeEventListener('hashchange', onHashChange)
})

// hash 子路由：#/admin/eval/runs/{id} → 运行详情独立页；否则 → Tab 列表
const detailRunId = computed(() => {
  const m = currentHash.value.match(/^#\/admin\/eval\/runs\/(\d+)$/)
  return m ? Number(m[1]) : null
})

/** 从详情页返回 → 回到「运行记录」Tab（activeTab 值可能未变、watch 不触发，这里显式刷新） */
function backToList() {
  activeTab.value = 'runs'
  window.location.hash = '#/admin/eval'
  runTabRef.value?.reload()
}
</script>

<template>
  <div class="eval-dashboard">
    <!-- 运行详情独立页（自带整页布局） -->
    <EvalRunDetail
      v-if="detailRunId !== null"
      :run-id="detailRunId"
      :datasets="datasets"
      @back="backToList"
    />

    <!-- Tab 列表：页头 + 下划线式 Tab -->
    <div v-else class="page-scroll">
      <div class="page-header">
        <div class="page-header-text">
          <h1 class="page-title">评测看板</h1>
          <p class="page-desc">黄金集 · 检索指标（Recall@k / Precision@k / MRR / nDCG）</p>
        </div>
      </div>

      <a-tabs v-model:activeKey="activeTab" class="eval-tabs">
        <a-tab-pane key="overview" tab="概览">
          <EvalOverviewTab :datasets="datasets" />
        </a-tab-pane>
        <a-tab-pane key="datasets" tab="数据集">
          <EvalDatasetTab :datasets="datasets" @need-reload-datasets="loadDatasets" />
        </a-tab-pane>
        <a-tab-pane key="runs" tab="运行记录">
          <EvalRunTab ref="runTabRef" :datasets="datasets" />
        </a-tab-pane>
      </a-tabs>
    </div>
  </div>
</template>

<style scoped>
.eval-dashboard {
  height: 100%;
}
/* Semi 下划线式 Tab：细 ink-bar、内容区与页头留出间距 */
.eval-tabs :deep(.ant-tabs-nav) {
  margin: 0 0 4px;
}
.eval-tabs :deep(.ant-tabs-content-holder) {
  padding-top: 12px;
}
</style>
