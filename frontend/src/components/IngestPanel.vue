<script setup lang="ts">
import { ref, computed, onMounted, nextTick } from 'vue'
import {
  InboxOutlined,
  ReloadOutlined,
  ApartmentOutlined,
  FolderOutlined,
} from '@ant-design/icons-vue'
import { message, Modal } from 'ant-design-vue'
import { pageDocuments, deleteDocument, type DocumentInfo } from '../api/upload'
import { listCollections, type DocCollection } from '../api/collection'
import DocListItem from './DocListItem.vue'
import mermaid from 'mermaid'

mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

const archSvg = ref('')
const archOpen = ref(false)
const archLoading = ref(false)

async function toggleArch() {
  archOpen.value = !archOpen.value
  if (!archOpen.value || archSvg.value) return
  archLoading.value = true
  await nextTick()
  try {
    const diagram = `graph TD
    A[原始字节流 + MIME类型] --> B{DocumentParserSelector<br>selectByMimeType}
    B --> C[MinerUDocumentParser<br>PDF/Word/PPT 复杂版面]
    B --> D[ExcelDocumentParser<br>表格]
    B --> E[ImageDocumentParser<br>VLM 图生文]
    B --> F[MarkdownDocumentParser<br>MD]
    B --> G[CsvDocumentParser<br>CSV]
    B --> H[TikaDocumentParser<br>text/* 兜底]
    C --> I[统一输出: ParsedDocument<br>blocks + metadata]
    D --> I
    E --> I
    F --> I
    G --> I
    H --> I`
    const { svg } = await mermaid.render('arch-diagram', diagram)
    archSvg.value = svg
  } finally {
    archLoading.value = false
  }
}

const emit = defineEmits<{ preview: [doc: DocumentInfo]; openTab: [doc: DocumentInfo] }>()

// ── 文档列表：集合折叠（懒加载）+ 独立文件直列 ──
// 不再用 listDocuments() 全量拉取（文档多时首屏慢）；首屏只取集合列表 + 独立文件首页，
// 集合内文档点击展开时才按 collectionId 懒加载。
const STANDALONE_PAGE_SIZE = 50
const collections = ref<DocCollection[]>([])
const standalone = ref<DocumentInfo[]>([])
const standaloneTotal = ref(0)
const expandedColIds = ref<number[]>([])
const colDocs = ref<Record<number, DocumentInfo[]>>({})
const colLoading = ref<Record<number, boolean>>({})
const docsLoading = ref(false)

const hasDocs = computed(() => collections.value.length > 0 || standalone.value.length > 0)

onMounted(() => loadDocs())

async function loadDocs() {
  docsLoading.value = true
  try {
    const [cols, res] = await Promise.all([
      listCollections(),
      pageDocuments({ page: 1, size: STANDALONE_PAGE_SIZE, unassigned: true }),
    ])
    collections.value = cols
    standalone.value = res.records
    standaloneTotal.value = res.total
  } catch {
    // 忽略
  } finally {
    docsLoading.value = false
  }
}

/** 展开/收起集合：首次展开按 collectionId 懒加载其文档 */
async function toggleCollection(col: DocCollection) {
  const idx = expandedColIds.value.indexOf(col.id)
  if (idx >= 0) {
    expandedColIds.value.splice(idx, 1)
    return
  }
  expandedColIds.value = [...expandedColIds.value, col.id]
  if (!colDocs.value[col.id]) {
    await loadColDocs(col.id)
  }
}

async function loadColDocs(colId: number) {
  colLoading.value[colId] = true
  try {
    const res = await pageDocuments({ page: 1, size: STANDALONE_PAGE_SIZE, collectionId: colId })
    colDocs.value[colId] = res.records
  } catch {
    colDocs.value[colId] = []
  } finally {
    colLoading.value[colId] = false
  }
}

/** 上传/删除后：重载集合 + 独立文件，并刷新已展开集合的文档缓存 */
async function reloadAll() {
  await loadDocs()
  for (const id of expandedColIds.value) {
    delete colDocs.value[id]
    await loadColDocs(id)
  }
}

async function confirmDelete(doc: DocumentInfo) {
  Modal.confirm({
    title: '确认删除',
    content: `确定要删除文档 "${doc.name}" 吗？${doc.chunkCount > 0 ? `（含 ${doc.chunkCount} 段向量数据）` : ''}`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteDocument(doc.docId)
        message.success(`已删除: ${doc.name}`)
        await reloadAll()
      } catch {
        message.error('删除失败')
      }
    },
  })
}
</script>

<template>
  <div class="ingest">
    <!-- 标题 -->
    <div class="panel-header">
      <InboxOutlined class="panel-icon" />
      <div>
        <h3 class="panel-title">知识库</h3>
        <p class="panel-desc">已入库文档（上传请至「文档管理」）</p>
      </div>
    </div>

    <!-- 已入库文档列表（集合折叠懒加载 + 独立文件直列） -->
    <div v-if="hasDocs || docsLoading" class="docs-section">
      <div class="docs-header">
        <span class="docs-count">文档 · 集合 {{ collections.length }} · 独立 {{ standaloneTotal }}</span>
        <a-button type="text" size="small" class="docs-refresh" @click="reloadAll">
          <template #icon><ReloadOutlined /></template>
        </a-button>
      </div>

      <div v-if="docsLoading" class="docs-loading">加载中…</div>
      <div v-else class="docs-list">
        <!-- 集合分组（点击展开懒加载） -->
        <div v-for="col in collections" :key="'col-' + col.id" class="col-group">
          <div class="col-header" @click="toggleCollection(col)">
            <span class="col-arrow">{{ expandedColIds.includes(col.id) ? '▾' : '▸' }}</span>
            <FolderOutlined class="col-icon" />
            <span class="col-name">{{ col.name }}</span>
            <span class="col-count">{{ col.docCount }}</span>
          </div>
          <div v-if="expandedColIds.includes(col.id)" class="col-docs">
            <div v-if="colLoading[col.id]" class="col-loading">加载中…</div>
            <template v-else>
              <DocListItem
                v-for="d in (colDocs[col.id] || [])"
                :key="d.docId"
                :doc="d"
                @preview="emit('preview', $event)"
                @open-tab="emit('openTab', $event)"
                @delete="confirmDelete"
              />
              <div v-if="!(colDocs[col.id] || []).length" class="col-empty">空集合</div>
            </template>
          </div>
        </div>

        <!-- 独立文件 -->
        <div v-if="collections.length && standalone.length" class="group-label">独立文件 · {{ standaloneTotal }}</div>
        <DocListItem
          v-for="d in standalone"
          :key="d.docId"
          :doc="d"
          @preview="emit('preview', $event)"
          @open-tab="emit('openTab', $event)"
          @delete="confirmDelete"
        />
        <div v-if="standaloneTotal > standalone.length" class="more-hint">
          仅显示前 {{ standalone.length }} / {{ standaloneTotal }} 个，更多见文档管理
        </div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else class="empty-state">
      <div class="empty-icon">
        <InboxOutlined />
      </div>
      <p class="empty-text">暂无文档</p>
      <p class="empty-hint">拖拽文件到上方区域开始入库</p>
    </div>

    <!-- 架构图（始终可见，不受文档有无影响） -->
    <div class="arch-section">
      <div class="arch-toggle" @click="toggleArch">
        <ApartmentOutlined /> 文档解析架构
        <span class="arch-arrow">{{ archOpen ? '▾' : '▸' }}</span>
      </div>
      <div v-if="archOpen" class="arch-body">
        <div v-if="archLoading" class="arch-loading">渲染中…</div>
        <div v-else class="arch-svg" v-html="archSvg"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ingest {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  height: 100%;
  overflow-y: auto;
}

/* ===== Panel Header ===== */
.panel-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 4px;
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
  line-height: 1.3;
}

.panel-desc {
  margin: 0;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

/* ===== Docs Section ===== */
.docs-section {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.docs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border-light);
}

.docs-count {
  font-family: var(--font-display);
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--color-ink-tertiary);
  text-transform: uppercase;
}

.docs-loading {
  padding: 16px;
  text-align: center;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

.docs-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* 集合折叠分组 */
.col-group {
  display: flex;
  flex-direction: column;
}

.col-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
}

.col-header:hover {
  background: var(--color-surface-secondary);
}

.col-arrow {
  font-size: 10px;
  color: var(--color-ink-tertiary);
  width: 10px;
  flex-shrink: 0;
}

.col-icon {
  font-size: 14px;
  color: var(--color-primary);
  flex-shrink: 0;
}

.col-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-ink);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.col-count {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  background: var(--color-surface-secondary);
  padding: 0 6px;
  border-radius: 8px;
  flex-shrink: 0;
}

.col-docs {
  display: flex;
  flex-direction: column;
  padding-left: 12px;
}

.col-loading,
.col-empty {
  padding: 8px 12px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
}

.group-label {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  padding: 10px 12px 4px;
  margin-top: 4px;
  letter-spacing: 0.04em;
}

.more-hint {
  padding: 8px 12px;
  font-size: 11px;
  color: var(--color-ink-tertiary);
  opacity: 0.7;
}

/* ===== Empty State ===== */
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--color-ink-tertiary);
}

.empty-icon {
  font-size: 32px;
  margin-bottom: 8px;
  opacity: 0.4;
}

.empty-text {
  font-size: 14px;
  margin: 0 0 4px;
  color: var(--color-ink-secondary);
}

.empty-hint {
  font-size: 12px;
  margin: 0;
}

/* ── 架构图 ── */
.arch-section { margin-top:auto; border-top:1px solid var(--color-border-light); flex-shrink:0; }
.arch-toggle { display:flex; align-items:center; gap:6px; padding:10px 14px; font-size:12px; color:var(--color-ink-tertiary); cursor:pointer; user-select:none; transition:background .12s; }
.arch-toggle:hover { background:var(--color-surface-secondary); }
.arch-arrow { margin-left:auto; font-size:11px; }
.arch-body { padding:0 14px 14px; overflow-x:auto; }
.arch-loading { text-align:center; padding:20px; font-size:12px; color:var(--color-ink-tertiary); }
.arch-svg :deep(svg) { max-width:100%; height:auto; }

@media (max-width: 768px) {
  .ingest { padding: 14px 14px 20px; }
}
</style>
