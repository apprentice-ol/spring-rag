<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { listDatasets, type EvalDataset } from '../api/eval'
import EvalOverviewTab from './EvalOverviewTab.vue'
import EvalDatasetTab from './EvalDatasetTab.vue'
import EvalRunTab from './EvalRunTab.vue'
import EvalRunDetail from './EvalRunDetail.vue'

const activeTab = ref('overview')
const datasets = ref<EvalDataset[]>([])

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

/** 从详情页返回 → 回到「运行记录」Tab */
function backToList() {
  activeTab.value = 'runs'
  window.location.hash = '#/admin/eval'
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
          <EvalRunTab :datasets="datasets" />
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
