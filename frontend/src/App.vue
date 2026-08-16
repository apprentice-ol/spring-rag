<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { ArrowLeftOutlined, RobotOutlined, DashboardOutlined, FolderOpenOutlined, BarChartOutlined, DeploymentUnitOutlined } from '@ant-design/icons-vue'
import EvalDashboard from './components/EvalDashboard.vue'
import ConsoleDashboard from './components/ConsoleDashboard.vue'
import DocumentManage from './components/DocumentManage.vue'
import ChatPanel from './components/ChatPanel.vue'
import ChatView from './components/ChatView.vue'
import DocPreview from './components/DocPreview.vue'
import TraceView from './components/TraceView.vue'
import ComparePanel from './components/ComparePanel.vue'
import AgentTracePanel from './components/AgentTracePanel.vue'
import TopTabs from './components/TopTabs.vue'
import type { MainNavKey } from './components/TopTabs.vue'
import { useHorizontalSplitter, useIsMobile } from './composables/useSplitter'
import { initChatState } from './composables/useChatState'

const theme = {
  token: {
    colorPrimary: '#0064fa',
    colorPrimaryHover: '#1657ff',
    borderRadius: 6,
    // antd 预设色板对齐 Semi 语义色（a-tag 跟随）
    colorPurple: '#0064fa',
    colorGreen: '#3fbf4f',
    colorRed: '#f93920',
    colorOrange: '#fbad2d',
    colorGold: '#fbad2d',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}

const backendOnline = ref<boolean | null>(null)

const isMobile = useIsMobile()

/** 顶部导航统一入口：管理跳后台；对话（含点品牌）确保回到主页 */
function onMainNav(key: MainNavKey) {
  if (key === 'admin') {
    goAdmin()
    return
  }
  if (isAdminPage.value) backToHome()
}

/** simple hash router: #/preview/[docId] → full-page preview; else → normal layout */
const currentHash = ref(window.location.hash)

function onHashChange() {
  currentHash.value = window.location.hash
}

onMounted(() => {
  pingBackend()
  initChatState()
  window.addEventListener('hashchange', onHashChange)
})
onUnmounted(() => {
  window.removeEventListener('hashchange', onHashChange)
})

const previewDocId = computed(() => {
  const m = currentHash.value.match(/^#\/preview\/(.+)$/)
  return m ? m[1] : null
})

const isPreviewPage = computed(() => previewDocId.value !== null)

// 全屏预览页：内容区宽度可拖拽（拉宽缩短），持久化记忆
const previewSplit = useHorizontalSplitter({
  initial: 960, min: 420, max: 2400, storageKey: 'rag_preview_w',
})

// 管理后台：左侧菜单（控制台 / 评测），hash 路由 #/admin[/console|eval]
const isAdminPage = computed(() => currentHash.value.startsWith('#/admin'))
const adminTab = computed(() => {
  const m = currentHash.value.match(/^#\/admin\/(.+)$/)
  // 取首段：eval/runs/2 → eval（菜单高亮 + 模块分发；子路径 eval 自行解析）
  return m ? m[1].split('/')[0] : 'console'
})

function goAdmin() {
  window.location.hash = '#/admin'
}
function onAdminMenu({ key }: { key: string }) {
  window.location.hash = '#/admin/' + key
}
function backToHome() {
  window.location.hash = '#/'
}

async function pingBackend() {
  try {
    await fetch('/api/rag/ping/health')
    backendOnline.value = true
  } catch {
    backendOnline.value = false
  }
}

function closePreview() {
  // 全屏预览页：尝试关闭标签页；失败则回主页 hash
  try { window.close() } catch { window.location.hash = '#/' }
}
</script>

<template>
  <a-config-provider :theme="theme">
    <!-- 全屏预览页：无侧边栏，无聊天 -->
    <template v-if="isPreviewPage">
      <div class="full-preview-page">
        <header class="full-preview-header">
          <a-button type="text" class="back-btn" @click="closePreview">
            <template #icon><ArrowLeftOutlined /></template>
            返回主页
          </a-button>
          <span class="preview-page-label">文档预览</span>
        </header>
        <div class="full-preview-body">
          <div class="preview-pane" :style="{ width: previewSplit.width.value + 'px' }">
            <DocPreview :doc-id="previewDocId!" key="preview-page" />
          </div>
          <div
            class="preview-splitter"
            :class="{ dragging: previewSplit.dragging.value }"
            @pointerdown="previewSplit.start"
          />
        </div>
      </div>
    </template>

    <!-- 管理后台：与主工作台共用顶栏导航（管理 tab 高亮）+ 左侧二级菜单 -->
    <template v-else-if="isAdminPage">
      <a-layout class="admin-page">
        <a-layout-header class="admin-header">
          <TopTabs active="admin" @select="onMainNav">
            <template #right>
              <span class="admin-sub">springai-rag · 平台管理</span>
            </template>
          </TopTabs>
        </a-layout-header>
        <a-layout>
          <a-layout-sider :width="176" class="admin-sider" theme="light">
            <a-menu mode="inline" :selected-keys="[adminTab]" @click="onAdminMenu" class="admin-menu">
              <a-menu-item key="console">
                <template #icon><DashboardOutlined /></template>
                控制台
              </a-menu-item>
              <a-menu-item key="docs">
                <template #icon><FolderOpenOutlined /></template>
                文档管理
              </a-menu-item>
              <a-menu-item key="eval">
                <template #icon><BarChartOutlined /></template>
                评测
              </a-menu-item>
              <a-menu-item key="trace">
                <template #icon><DeploymentUnitOutlined /></template>
                链路追踪
              </a-menu-item>
              <a-menu-item key="compare">
                <template #icon><RobotOutlined /></template>
                Agent 对照
              </a-menu-item>
              <a-menu-item key="agent-traces">
                <template #icon><RobotOutlined /></template>
                Agent 轨迹
              </a-menu-item>
            </a-menu>
          </a-layout-sider>
          <a-layout-content class="admin-content">
            <ConsoleDashboard v-if="adminTab === 'console'" />
            <DocumentManage v-else-if="adminTab === 'docs'" />
            <EvalDashboard v-else-if="adminTab === 'eval'" />
            <TraceView v-else-if="adminTab === 'trace'" />
            <ComparePanel v-else-if="adminTab === 'compare'" />
            <AgentTracePanel v-else-if="adminTab === 'agent-traces'" />
          </a-layout-content>
        </a-layout>
      </a-layout>
    </template>

    <!-- 主工作台：顶部导航 + 对话区（文档浏览/上传在管理后台「文档管理」） -->
    <template v-else>
      <div class="app">
        <!-- 顶部导航栏 -->
        <header class="app-header">
          <TopTabs active="chat" @select="onMainNav">
            <template #right>
              <div class="connection-status" :class="{ connected: backendOnline === true, disconnected: backendOnline === false }">
                <span class="status-dot"></span>
                <span class="status-text">
                  <template v-if="backendOnline === null">检测中…</template>
                  <template v-else-if="backendOnline">后端在线 :9081</template>
                  <template v-else>后端离线</template>
                </span>
              </div>
            </template>
          </TopTabs>
        </header>

        <!-- 主体区域：桌面端 ChatPanel（自带可拖拽会话栏）/ 移动端 ChatView -->
        <div class="app-body">
          <ChatPanel v-if="!isMobile" />
          <main v-else class="mobile-main">
            <div class="mobile-tab-page">
              <ChatView />
            </div>
          </main>
        </div>
      </div>
    </template>
  </a-config-provider>
</template>

<style scoped>
/* ─ Full Preview Page ─ */
.full-preview-page {
  display: flex; flex-direction: column; height: 100vh; overflow: hidden;
  background: var(--color-bg);
}
.full-preview-header {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 20px; border-bottom: 1px solid var(--color-border);
  flex-shrink: 0; background: var(--color-surface);
}
.full-preview-body {
  flex: 1; min-height: 0; overflow: hidden;
  display: flex;
}
/* 预览内容区：宽度由拖拽分割条控制（max-width 兜底防拖出视口） */
.preview-pane {
  flex-shrink: 0;
  height: 100%;
  min-width: 0;
  max-width: calc(100vw - 80px);
}
.preview-splitter {
  flex-shrink: 0;
  width: 7px;
  cursor: col-resize;
  border-right: 1px solid var(--color-border);
  background: transparent;
  transition: background 0.15s;
}
.preview-splitter:hover,
.preview-splitter.dragging {
  background: var(--color-primary);
}
.preview-page-label {
  font-size: 13px; color: var(--color-ink-secondary);
}
.back-btn { font-size: 16px; color: var(--color-primary); flex-shrink: 0; }

/* ─ Admin Layout（antd a-layout） ─ */
.admin-page {
  height: 100vh; overflow: hidden; background: var(--color-bg);
}
.admin-header {
  display: flex; align-items: center;
  padding: 0; height: 48px; line-height: 48px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.admin-sub {
  font-size: 12px; color: var(--color-ink-tertiary);
}
.admin-sider {
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
  flex-shrink: 0;
  overflow-y: auto;
}
.admin-menu {
  border-inline-end: none !important;
  padding-top: 6px;
}
.admin-content {
  min-width: 0; overflow: hidden; background: var(--color-bg);
}
@media (max-width: 768px) {
  .admin-sub { display: none; }
}

/* ─ Main Layout：顶栏 tab 导航 + 单侧栏 ─ */
.app { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
.app-header {
  display: flex; align-items: center;
  padding: 0; height: 48px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border); z-index: 100; flex-shrink: 0;
}
.connection-status { display: flex; align-items: center; gap: 7px; padding: 4px 12px; border-radius: 20px; font-size: 12px; background: var(--color-surface-secondary); border: 1px solid var(--color-border-light); }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #ccc; transition: background 0.3s; }
.connection-status.connected .status-dot { background: var(--color-success); box-shadow: 0 0 6px rgba(63,191,79,0.4); }
.connection-status.disconnected .status-dot { background: var(--color-danger); box-shadow: 0 0 6px rgba(249,57,32,0.4); }
.status-text { color: var(--color-ink-secondary); }
.app-body { flex: 1; display: flex; min-height: 0; overflow: hidden; position: relative; }

/* 移动端：聊天内容区 */
.mobile-main { flex: 1; min-width: 0; background: var(--color-bg); display: flex; flex-direction: column; }
.mobile-tab-page { flex: 1; min-height: 0; display: flex; flex-direction: column; }

@media (max-width: 768px) {
  /* 移动端隐藏连接状态文字，只留圆点，省空间 */
  .status-text { display: none; }
  .connection-status { padding: 4px 8px; }
}
</style>
