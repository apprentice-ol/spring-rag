<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { BarChartOutlined } from '@ant-design/icons-vue'
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
    <!-- 运行详情独立页 -->
    <EvalRunDetail
      v-if="detailRunId !== null"
      :run-id="detailRunId"
      :datasets="datasets"
      @back="backToList"
    />

    <!-- Tab 列表 -->
    <template v-else>
      <div class="panel-header">
        <BarChartOutlined class="panel-icon" />
        <div>
          <h3 class="panel-title">评测看板</h3>
          <p class="panel-desc">黄金集 · 检索指标（Recall@k / Precision@k / MRR / nDCG）</p>
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
    </template>
  </div>
</template>

<style scoped>
.eval-dashboard {
  padding: 20px;
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
  overflow-y: auto;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.panel-icon {
  font-size: 20px;
  color: var(--color-primary);
}
.panel-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-ink);
}
.panel-desc {
  margin: 0;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
.eval-tabs {
  flex: 1;
  min-height: 0;
}
</style>
