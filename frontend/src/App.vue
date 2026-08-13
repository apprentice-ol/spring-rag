<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { ApiOutlined, MenuFoldOutlined, MenuUnfoldOutlined, ArrowLeftOutlined, InboxOutlined, RobotOutlined, BarChartOutlined, DashboardOutlined, FolderOpenOutlined, SettingOutlined, DeploymentUnitOutlined } from '@ant-design/icons-vue'
import IngestPanel from './components/IngestPanel.vue'
import EvalDashboard from './components/EvalDashboard.vue'
import ConsoleDashboard from './components/ConsoleDashboard.vue'
import DocumentManage from './components/DocumentManage.vue'
import ChatPanel from './components/ChatPanel.vue'
import ChatView from './components/ChatView.vue'
import DocPreview from './components/DocPreview.vue'
import TraceView from './components/TraceView.vue'
import ComparePanel from './components/ComparePanel.vue'
import AgentTracePanel from './components/AgentTracePanel.vue'
import { useHorizontalSplitter, useIsMobile } from './composables/useSplitter'
import { initChatState } from './composables/useChatState'
import type { DocumentInfo } from './api/upload'

const theme = {
  token: {
    colorPrimary: '#0f766e',
    colorPrimaryHover: '#065f55',
    borderRadius: 6,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}

const backendOnline = ref<boolean | null>(null)
const sidebarCollapsed = ref(false)
const selectedDoc = ref<DocumentInfo | null>(null)

// 桌面端：预览前记住侧边栏是否折叠，返回时恢复（修复“预览返回后侧边栏一直折叠”的问题）
const sidebarCollapsedBeforePreview = ref(false)

const isMobile = useIsMobile()

// 移动端：底部 Tab（知识库 / 聊天；对话列表在聊天页左侧抽屉）
const mobileTab = ref<'docs' | 'chat'>('chat')

// 桌面端 sidebar 可拖拽改宽（移动端走 Tab，不拖拽）
const sidebarSplit = useHorizontalSplitter({
  initial: 360, min: 280, max: 560, storageKey: 'rag_sidebar_w',
})
const sidebarWidthStyle = computed(() => `${sidebarSplit.width.value}px`)

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

/** 顶部汉堡按钮：仅桌面端有效（折叠/展开侧边栏） */
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}

function onPreview(doc: DocumentInfo) {
  // 右侧嵌入预览（保留对话历史）
  selectedDoc.value = doc
  // 记住桌面端侧边栏原状态，关掉它给预览让位
  if (!isMobile.value) {
    sidebarCollapsedBeforePreview.value = sidebarCollapsed.value
    sidebarCollapsed.value = true
  }
}

function onOpenNewTab(doc: DocumentInfo) {
  window.open('/#/preview/' + doc.docId, '_blank')
}

function closePreview() {
  // 全屏预览页：尝试关闭标签页；失败则回主页 hash
  try { window.close() } catch { window.location.hash = '#/' }
}

function backToChat() {
  selectedDoc.value = null
  // 恢复桌面端侧边栏到预览前的状态
  if (!isMobile.value) {
    sidebarCollapsed.value = sidebarCollapsedBeforePreview.value
  }
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

    <!-- 管理后台：antd 布局（左侧菜单：控制台 / 文档管理 / 评测） -->
    <template v-else-if="isAdminPage">
      <a-layout class="admin-page">
        <a-layout-header class="admin-header">
          <a-button type="text" class="back-btn" @click="backToHome">
            <template #icon><ArrowLeftOutlined /></template>
            返回主页
          </a-button>
          <span class="admin-brand">管理后台</span>
          <span class="admin-sub">springai-rag · 平台管理</span>
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

    <!-- 正常布局 -->
    <template v-else>
      <div class="app">
        <!-- 顶部导航栏 -->
        <header class="app-header">
          <div class="header-left">
            <!-- 桌面端：汉堡按钮折叠/展开侧边栏 -->
            <a-button
              v-if="!isMobile"
              type="text"
              class="sidebar-toggle"
              @click="toggleSidebar"
            >
              <template #icon>
                <MenuFoldOutlined v-if="!sidebarCollapsed" />
                <MenuUnfoldOutlined v-else />
              </template>
            </a-button>

            <!-- 预览模式：显示返回按钮 -->
            <template v-if="selectedDoc">
              <a-button type="text" class="back-btn" @click="backToChat">
                <template #icon><ArrowLeftOutlined /></template>
                <span class="back-label">返回</span>
              </a-button>
              <div class="breadcrumb">
                <span class="breadcrumb-label">文档预览</span>
                <span class="breadcrumb-sep">/</span>
                <span class="breadcrumb-name">{{ selectedDoc.name }}</span>
              </div>
            </template>

            <template v-else>
              <div class="brand">
                <div class="brand-icon"><ApiOutlined /></div>
                <div class="brand-text">
                  <span class="brand-name">RAG Workbench</span>
                  <span class="brand-sub">Spring AI 知识库</span>
                </div>
              </div>
            </template>
          </div>

          <div class="header-right">
            <a-button type="text" class="eval-entry-btn" title="管理后台（控制台 / 评测）" @click="goAdmin">
              <template #icon><SettingOutlined /></template>
              <span class="eval-entry-label">管理后台</span>
            </a-button>
            <div class="connection-status" :class="{ connected: backendOnline === true, disconnected: backendOnline === false }">
              <span class="status-dot"></span>
              <span class="status-text">
                <template v-if="backendOnline === null">检测中…</template>
                <template v-else-if="backendOnline">后端在线 :9081</template>
                <template v-else>后端离线</template>
              </span>
            </div>
          </div>
        </header>

        <!-- 主体区域 -->
        <div class="app-body">
          <!-- ── 桌面端：sidebar + 分割条 + 主内容 ── -->
          <template v-if="!isMobile">
            <aside
              class="sidebar"
              :class="{ collapsed: sidebarCollapsed || !!selectedDoc }"
              :style="{ width: (sidebarCollapsed || selectedDoc) ? '0px' : sidebarWidthStyle }"
            >
              <div class="sidebar-inner" :style="{ width: sidebarWidthStyle }">
                <IngestPanel @preview="onPreview" @open-tab="onOpenNewTab" />
              </div>
            </aside>

            <!-- 桌面端拖拽分割条 -->
            <div
              v-if="!sidebarCollapsed && !selectedDoc"
              class="splitter-bar"
              :class="{ dragging: sidebarSplit.dragging.value }"
              @pointerdown="sidebarSplit.start"
            />

            <main class="main-content">
              <DocPreview
                v-if="selectedDoc"
                :doc-id="selectedDoc.docId"
                :mime-type="selectedDoc.mimeType"
                :doc-name="selectedDoc.name"
                :source-location="selectedDoc.sourceLocation"
                embedded
                @close="backToChat"
              />
              <ChatPanel v-else />
            </main>
          </template>

          <!-- ── 移动端：Tab 内容区 ── -->
          <template v-else>
            <main class="mobile-main">
              <!-- 预览文档时全屏展示，tab 栏隐藏 -->
              <template v-if="selectedDoc">
                <DocPreview
                  :doc-id="selectedDoc.docId"
                  :mime-type="selectedDoc.mimeType"
                  :doc-name="selectedDoc.name"
                  :source-location="selectedDoc.sourceLocation"
                  embedded
                  @close="backToChat"
                />
              </template>

              <!-- 知识库 tab -->
              <div v-if="mobileTab === 'docs'" class="mobile-tab-page">
                <IngestPanel @preview="onPreview" @open-tab="onOpenNewTab" />
              </div>

              <!-- 聊天 tab（对话列表在左侧抽屉） -->
              <div v-else class="mobile-tab-page">
                <ChatView />
              </div>
            </main>
          </template>
        </div>

        <!-- 移动端底部 TabBar（预览文档时隐藏） -->
        <nav v-if="isMobile && !selectedDoc" class="mobile-tabbar">
          <div class="tab" :class="{ active: mobileTab === 'docs' }" @click="mobileTab = 'docs'">
            <InboxOutlined class="tab-icon" />
            <span class="tab-label">知识库</span>
          </div>
          <div class="tab" :class="{ active: mobileTab === 'chat' }" @click="mobileTab = 'chat'">
            <RobotOutlined class="tab-icon" />
            <span class="tab-label">聊天</span>
          </div>
        </nav>
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

/* ─ Admin Layout（antd a-layout） ─ */
.admin-page {
  height: 100vh; overflow: hidden; background: var(--color-bg);
}
.admin-header {
  display: flex; align-items: center; gap: 12px;
  padding: 0 20px; height: 56px; line-height: 56px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}
.admin-brand {
  font-size: 14px; font-weight: 600; color: var(--color-ink);
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

/* ─ Normal Layout ─ */
.app { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
.app-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 0 20px; height: 56px;
  background: rgba(255,255,255,0.85); backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--color-border); z-index: 100; flex-shrink: 0;
}
.header-left { display: flex; align-items: center; gap: 6px; min-width: 0; }
.sidebar-toggle { font-size: 16px; color: var(--color-ink-secondary); flex-shrink: 0; }
.back-btn { font-size: 16px; color: var(--color-primary); flex-shrink: 0; }
.back-label { font-size: 13px; margin-left: 2px; }
.breadcrumb { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: 13px; }
.breadcrumb-label { color: var(--color-ink-secondary); flex-shrink: 0; }
.breadcrumb-sep { color: var(--color-border); flex-shrink: 0; }
.breadcrumb-name { color: var(--color-ink); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.brand { display: flex; align-items: center; gap: 10px; }
.brand-icon { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, var(--color-primary), #14b8a6); color: #fff; border-radius: var(--radius-sm); font-size: 16px; }
.brand-text { display: flex; flex-direction: column; line-height: 1.2; }
.brand-name { font-family: var(--font-display); font-size: 14px; font-weight: 600; letter-spacing: 0.02em; color: var(--color-ink); }
.brand-sub { font-size: 11px; color: var(--color-ink-tertiary); letter-spacing: 0.04em; }
.header-right { display: flex; align-items: center; gap: 16px; }
.connection-status { display: flex; align-items: center; gap: 7px; padding: 4px 12px; border-radius: 20px; font-size: 12px; background: var(--color-surface-secondary); border: 1px solid var(--color-border-light); }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #ccc; transition: background 0.3s; }
.connection-status.connected .status-dot { background: #22c55e; box-shadow: 0 0 6px rgba(34,197,94,0.4); }
.connection-status.disconnected .status-dot { background: #ef4444; box-shadow: 0 0 6px rgba(239,68,68,0.4); }
.status-text { color: var(--color-ink-secondary); }
.app-body { flex: 1; display: flex; min-height: 0; overflow: hidden; position: relative; }

/* 桌面端侧边栏：宽度由拖拽控制，折叠时收为 0 */
.sidebar {
  min-width: 0; border-right: 1px solid var(--color-border);
  background: var(--color-surface);
  transition: width 0.25s ease, opacity 0.25s ease;
  overflow: hidden; flex-shrink: 0;
}
.sidebar.collapsed { opacity: 0; border-right: none; }
.sidebar-inner { height: 100%; overflow-y: auto; position: relative; }

.main-content { flex: 1; min-width: 0; background: var(--color-bg); }

/* 移动端：Tab 内容区 + 底部 TabBar */
.mobile-main { flex: 1; min-width: 0; background: var(--color-bg); display: flex; flex-direction: column; }
.mobile-tab-page { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.mobile-tabbar {
  display: flex;
  height: 56px;
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
  z-index: 100;
}
.tab {
  flex: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
  color: var(--color-ink-tertiary);
  cursor: pointer;
  transition: color 0.15s;
  -webkit-tap-highlight-color: transparent;
  user-select: none;
}
.tab.active { color: var(--color-primary); }
.tab-icon { font-size: 20px; }
.tab-label { font-size: 11px; }

@media (max-width: 768px) {
  .app-header { padding: 0 12px; }
  .brand-sub, .connector-count { display: none; }
  .back-label { display: none; }
  /* 移动端隐藏连接状态文字，只留圆点，省空间 */
  .status-text { display: none; }
  .connection-status { padding: 4px 8px; }
}
</style>
