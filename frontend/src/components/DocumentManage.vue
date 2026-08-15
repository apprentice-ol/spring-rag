<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import {
  UploadOutlined,
  InboxOutlined,
  SearchOutlined,
  ReloadOutlined,
  EyeOutlined,
  DeleteOutlined,
  FolderOutlined,
  FileTextOutlined,
  PlusOutlined,
  EditOutlined,
  FolderAddOutlined,
  SwapOutlined,
} from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import { uploadDocument, pageDocuments, deleteDocument, type DocumentInfo } from '../api/upload'
import {
  listCollections,
  createCollection,
  renameCollection,
  deleteCollection,
  type DocCollection,
} from '../api/collection'
import DocPreview from './DocPreview.vue'
import CollectionDocTable from './CollectionDocTable.vue'
import CollectionPickerModal from './CollectionPickerModal.vue'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'

// ── 查询条件（仅作用于独立文件段）──
const keyword = ref('')
const status = ref<string | undefined>(undefined)

// ── 集合 / 独立文件 ──
const collections = ref<DocCollection[]>([])
const records = ref<DocumentInfo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

// ── 表格自适应高度（scroll.y 固定表头，表格内部滚动）──
const tableWrap = ref<HTMLElement>()
const tableBodyHeight = ref<number | undefined>(undefined)

function measure() {
  const el = tableWrap.value
  if (!el) return
  const wrapH = el.getBoundingClientRect().height
  const headH = el.querySelector('.ant-table-thead')?.getBoundingClientRect().height ?? 40
  tableBodyHeight.value = Math.max(120, Math.floor(wrapH - headH))
}

// ── 异构行：集合（col）在上、独立文件（doc）在下 ──
interface ColRow extends DocCollection {
  _type: 'col'
}
interface DocRow extends DocumentInfo {
  _type: 'doc'
}
const topRows = computed<(ColRow | DocRow)[]>(() => [
  ...collections.value.map((c) => ({ ...c, _type: 'col' as const })),
  ...records.value.map((d) => ({ ...d, _type: 'doc' as const })),
])

function rowKey(r: ColRow | DocRow): string {
  return r._type === 'col' ? 'col-' + r.id : 'doc-' + r.docId
}

// ── 展开集合（懒加载嵌套分页表）──
const expandedKeys = ref<string[]>([])
// 集合展开状态用 v-model:expandedRowKeys 绑定到 a-table 顶层 prop。
// 注：antdv 的 expandedRowKeys 是 Table 的顶层 prop（vc-table 只读 props.expandedRowKeys），
// 放进 :expandable 对象里不生效——曾因此导致程序化赋值不被渲染。
// ── 按名称搜索时，自动展开命中文档所属的集合 ──
// 全局查匹配文档（不限集合）→ 取 collectionId → 展开这些集合；嵌套表透传 keyword 只显示匹配项。
// searchAddedKeys 记录"由搜索驱动展开"的 key，清空搜索时移除它们，保留用户手动展开的。
const searchAddedKeys = ref<string[]>([])
// a-table 重建 key：搜索/重置后自增，强制 antdv 重新挂载、用最新 expandedKeys 初始化展开状态。
// 原因：antdv v4 受控 expandedRowKeys 对程序化赋值增/减不对称——增加能展开、清空不折叠，
// 故在 expandedKeys 净变化的搜索态切换时重建表格，绕过该问题。
const tableKey = ref(0)

async function applySearchExpand() {
  // 先移除上一轮搜索驱动的展开
  if (searchAddedKeys.value.length) {
    const prev = new Set(searchAddedKeys.value)
    expandedKeys.value = expandedKeys.value.filter((k) => !prev.has(k))
    searchAddedKeys.value = []
  }
  const kw = keyword.value.trim()
  if (!kw) return
  try {
    // size 上限 100（后端 /docs/page 封顶 100）；命中超 100 仅展开前 100 条涉及的集合
    const res = await pageDocuments({ page: 1, size: 100, keyword: kw })
    const hitColKeys = new Set<string>()
    for (const d of res.records) {
      if (d.collectionId != null) hitColKeys.add('col-' + d.collectionId)
    }
    const toAdd = [...hitColKeys].filter((k) => !expandedKeys.value.includes(k))
    if (toAdd.length) {
      expandedKeys.value = [...expandedKeys.value, ...toAdd]
      searchAddedKeys.value = toAdd
    }
  } catch {
    // 全局命中查询失败不阻断独立文件段展示
  }
}

// ── 多选（仅独立文件行可勾选；集合行禁用 checkbox）──
const selectedKeys = ref<string[]>([])
const rowSelection = computed(() => ({
  selectedRowKeys: selectedKeys.value,
  onChange: (keys: (string | number)[]) => {
    selectedKeys.value = keys.map(String)

  },
  getCheckboxProps: (r: ColRow | DocRow) => ({ disabled: r._type === 'col' }),
}))
const selectedDocIds = computed(() => selectedKeys.value.filter((k) => k.startsWith('doc-')).map((k) => k.slice(4)))

// ── 列定义（可拖宽：v-resize 指令 + headerCell 插槽，见 composables/useResizableColumns）──
const columns = useResizableColumns([
  { title: '名称', dataIndex: 'name', key: 'name', width: 280, ellipsis: true },
  { title: '类型', dataIndex: 'mimeType', key: 'mimeType', width: 150, ellipsis: true },
  { title: '来源', dataIndex: 'sourceType', key: 'sourceType', width: 100 },
  { title: '文档/向量块', dataIndex: 'count', key: 'count', width: 110, align: 'center' as const },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90, align: 'center' },
  { title: '时间', dataIndex: 'time', key: 'time', width: 165 },
  { title: '操作', key: 'action', width: 180, fixed: 'center' as const },
])

const SOURCE_LABEL: Record<string, string> = {
  FILE: '文件上传',
  URL: '远程拉取',
  API: '接口导入',
}
const STATUS_COLOR: Record<string, string> = {
  DONE: 'green',
  PROCESSING: 'processing',
  PENDING: 'orange',
  FAILED: 'red',
}
const STATUS_TEXT: Record<string, string> = {
  DONE: '完成',
  PROCESSING: '处理中',
  PENDING: '待处理',
  FAILED: '失败',
}

let resizeObserver: ResizeObserver | undefined

onMounted(() => {
  reloadAll()
  measure()
  resizeObserver = new ResizeObserver(measure)
  if (tableWrap.value) resizeObserver.observe(tableWrap.value)
})
onUnmounted(() => resizeObserver?.disconnect())

async function loadCollections() {
  try {
    const data = await listCollections()
    // 防御：后端可能因路径未注册返回非数组（如命中 /docs/{docId}），保证始终为数组
    collections.value = Array.isArray(data) ? data : []
  } catch {
    collections.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const res = await pageDocuments({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: status.value,
      unassigned: true,
    })
    records.value = res.records
    total.value = res.total
  } catch {
    message.error('文档列表加载失败')
  } finally {
    loading.value = false
    nextTick(measure)
  }
  // 搜索关键词命中集合内文档时，自动展开对应集合（展开内容透传 keyword 只显示匹配项）
  await applySearchExpand()
}

async function reloadAll() {
  await Promise.all([loadCollections(), load()])
}

// ── 上传入库（dialog，含分块路线选择）──
const uploadModalOpen = ref(false)
const uploading = ref(false)
const picked = ref<File | null>(null)
/** 分块路线：false=语义感知（block-aware，默认）；true=纯文本（不保 block 元数据） */
const plainTextRoute = ref(false)

function openUpload() {
  picked.value = null
  uploadModalOpen.value = true
}
function beforeUpload(file: File) {
  picked.value = file
  return false
}
function clearPick() {
  picked.value = null
}
async function doUpload() {
  if (!picked.value) return
  uploading.value = true
  try {
    const res = await uploadDocument(picked.value, undefined, plainTextRoute.value)
    message.success(`已入库 ${res.chunkCount} 段（${plainTextRoute.value ? '纯文本' : '语义感知'}）`)
    picked.value = null
    uploadModalOpen.value = false
    await reloadAll()
  } catch (e: unknown) {
    const msg = (e as ErrResp)?.response?.data?.message
    message.error(msg || '上传失败，请看后端日志')
    await reloadAll()
  } finally {
    uploading.value = false
  }
}

async function doSearch() {
  page.value = 1
  await load()
  tableKey.value++
}

async function doReset() {
  keyword.value = ''
  status.value = undefined
  page.value = 1
  await load()
  tableKey.value++
}

function onPage(p: number, s: number) {
  page.value = p
  size.value = s
  selectedKeys.value = []
  load()
}

// ── 集合新建 / 改名 ──
const colModalOpen = ref(false)
const editingColId = ref<number | null>(null)
const editingColName = ref('')
const editingColDesc = ref('')

function openCreateCol() {
  editingColId.value = null
  editingColName.value = ''
  editingColDesc.value = ''
  colModalOpen.value = true
}
function openRenameCol(c: DocCollection) {
  editingColId.value = c.id
  editingColName.value = c.name
  editingColDesc.value = c.description || ''
  colModalOpen.value = true
}
async function saveCol() {
  if (!editingColName.value.trim()) {
    message.warning('请输入名称')
    return
  }
  try {
    if (editingColId.value) {
      await renameCollection(editingColId.value, editingColName.value.trim(), editingColDesc.value)
    } else {
      await createCollection(editingColName.value.trim(), editingColDesc.value)
    }
    message.success('已保存')
    colModalOpen.value = false
    await loadCollections()
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '保存失败')
  }
}

function confirmDeleteCol(c: DocCollection) {
  Modal.confirm({
    title: '删除文件集',
    content: `确定删除「${c.name}」？集合内 ${c.docCount} 个文档将变为独立文件，不会被删除（向量数据保留）。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const r = await deleteCollection(c.id)
        message.success(`已删除，${r.unlinked} 个文档转为独立文件`)
        await reloadAll()
      } catch (e: unknown) {
        message.error((e as ErrResp)?.response?.data?.message || '删除失败')
      }
    },
  })
}

// ── 归集（批量 / 单文档）──
const pickerOpen = ref(false)
const pickerDocIds = ref<string[]>([])

function openBatchAssign() {
  if (!selectedDocIds.value.length) return
  pickerDocIds.value = selectedDocIds.value
  pickerOpen.value = true
}
function openAssignOne(doc: DocumentInfo) {
  pickerDocIds.value = [doc.docId]
  pickerOpen.value = true
}
async function onAssigned() {
  selectedKeys.value = []
  await reloadAll()
}

// ── 删除文档（独立文件）──
function confirmDeleteDoc(doc: DocumentInfo) {
  Modal.confirm({
    title: '确认删除',
    content: `确定删除文档 "${doc.name}" 吗？${doc.chunkCount > 0 ? `（含 ${doc.chunkCount} 段向量数据）` : ''}`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteDocument(doc.docId)
        message.success(`已删除: ${doc.name}`)
        if (records.value.length === 1 && page.value > 1) page.value -= 1
        await load()
      } catch {
        message.error('删除失败')
      }
    },
  })
}

function batchDelete() {
  const ids = selectedDocIds.value
  if (!ids.length) return
  Modal.confirm({
    title: '批量删除',
    content: `确定删除选中的 ${ids.length} 个文档吗？（含对应向量数据）`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        for (const id of ids) await deleteDocument(id)
        message.success(`已删除 ${ids.length} 个文档`)
        selectedKeys.value = []
        await load()
      } catch {
        message.error('批量删除失败')
      }
    },
  })
}

// ── 预览抽屉 ──
const previewDoc = ref<DocumentInfo | null>(null)
function openPreview(doc: DocumentInfo) {
  previewDoc.value = doc
}
function onPreviewOpenChange(open: boolean) {
  if (!open) previewDoc.value = null
}

function fmtTime(t: string | undefined): string {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 19)
}

interface ErrResp {
  response?: { data?: { message?: string } }
}
</script>

<template>
  <div class="doc-manage page-scroll">
    <!-- 页头：标题 + 主操作 -->
    <div class="page-header">
      <div class="page-header-text">
        <h1 class="page-title">文档管理</h1>
        <p class="page-desc">文件集与独立文件的入库状态、归集与删除</p>
      </div>
      <div class="page-actions">
        <a-button @click="openCreateCol">
          <template #icon><FolderAddOutlined /></template>新建文件集
        </a-button>
        <a-button type="primary" @click="openUpload">
          <template #icon><UploadOutlined /></template>上传文档
        </a-button>
      </div>
    </div>

    <!-- 查询条件卡 -->
    <div class="filter-card">
      <div class="filter-row">
        <div class="filter-item">
          <span class="filter-label">关键词</span>
          <a-input
            v-model:value="keyword"
            placeholder="文件名关键词"
            allow-clear
            style="width: 220px"
            @press-enter="doSearch"
          >
            <template #prefix><SearchOutlined /></template>
          </a-input>
        </div>
        <div class="filter-item">
          <span class="filter-label">入库状态</span>
          <a-select v-model:value="status" placeholder="全部" allow-clear style="width: 140px">
            <a-select-option v-for="(label, v) in STATUS_TEXT" :key="v" :value="v">{{ label }}</a-select-option>
          </a-select>
        </div>
        <div class="filter-actions">
          <a-button type="primary" @click="doSearch">查询</a-button>
          <a-button @click="doReset">重置</a-button>
        </div>
      </div>
    </div>

    <!-- 表格卡：工具栏（批量操作）+ 异构表 + 分页 -->
    <div class="table-card">
      <div class="table-toolbar">
        <div class="toolbar-left">
          <template v-if="selectedDocIds.length > 0">
            <span class="selected-count">已选 {{ selectedDocIds.length }} 项</span>
            <a-button size="small" @click="openBatchAssign">
              <template #icon><SwapOutlined /></template>移入集合
            </a-button>
            <a-button size="small" danger @click="batchDelete">
              <template #icon><DeleteOutlined /></template>批量删除
            </a-button>
          </template>
          <span v-else class="toolbar-hint">文件集 {{ collections.length }} 个 · 独立文件 {{ total }} 个</span>
        </div>
        <div class="toolbar-right">
          <a-button type="text" size="small" title="刷新" @click="reloadAll">
            <template #icon><ReloadOutlined /></template>
          </a-button>
        </div>
      </div>

      <!-- 异构表：集合行（可展开）在上、独立文件行在下 -->
      <div ref="tableWrap" class="table-wrap">
        <a-table
          :key="tableKey"
          :data-source="topRows"
          :columns="columns"
          :loading="loading"
          :pagination="false"
          size="middle"
          :row-key="rowKey"
          :row-class-name="(r: ColRow | DocRow) => (r._type === 'col' ? 'row-collection' : '')"
          :row-selection="rowSelection"
          v-model:expandedRowKeys="expandedKeys"
          :scroll="{ x: 1000, y: tableBodyHeight }"
        >
          <template #headerCell="{ column }">
            <!-- 仅业务列渲染自定义表头；antdv 内部展开列的 title 是数组占位 ['']，
                 走文本插值会被 Vue JSON.stringify 成 [""]，须过滤掉 -->
            <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="columns">{{ column.title }}</span>
          </template>
          <template #bodyCell="{ column, record }">
            <!-- 集合行 -->
            <template v-if="record._type === 'col'">
              <template v-if="column.key === 'name'">
                <span class="col-name"><FolderOutlined class="col-icon" />{{ record.name }}</span>
              </template>
              <template v-else-if="column.key === 'count'">
                <span class="col-count">{{ record.docCount }} 个文档</span>
              </template>
              <template v-else-if="column.key === 'time'">{{ fmtTime(record.createTime) }}</template>
              <template v-else-if="column.key === 'action'">
                <a-button type="link" size="small" @click="openRenameCol(record)"><EditOutlined />改名</a-button>
                <a-button type="link" size="small" danger @click="confirmDeleteCol(record)"><DeleteOutlined />删除</a-button>
              </template>
              <template v-else>—</template>
            </template>
            <!-- 独立文件行 -->
            <template v-else>
              <template v-if="column.key === 'name'">
                <a class="doc-name" @click="openPreview(record)">
                  <FileTextOutlined class="doc-name-icon" />{{ record.name }}
                </a>
              </template>
              <template v-else-if="column.key === 'count'">{{ record.chunkCount }}</template>
              <template v-else-if="column.key === 'time'">{{ fmtTime(record.createdAt) }}</template>
              <template v-else-if="column.key === 'sourceType'">
                {{ SOURCE_LABEL[record.sourceType] || record.sourceType || '—' }}
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="STATUS_COLOR[record.status] || 'default'">{{ STATUS_TEXT[record.status] || record.status }}</a-tag>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-button type="link" size="small" @click="openPreview(record)"><EyeOutlined />预览</a-button>
                <a-button type="link" size="small" @click="openAssignOne(record)"><SwapOutlined />归集</a-button>
                <a-button type="link" size="small" danger @click="confirmDeleteDoc(record)"><DeleteOutlined />删除</a-button>
              </template>
            </template>
          </template>
          <!-- 仅集合行显示展开图标（独立文件行不渲染），用 slot 精确控制 -->
          <template #expandIcon="{ expanded, onExpand, record }">
            <button
              v-if="record._type === 'col'"
              type="button"
              :class="['ant-table-row-expand-icon', expanded ? 'ant-table-row-expand-icon-expanded' : 'ant-table-row-expand-icon-collapsed']"
              :aria-label="expanded ? 'Collapse row' : 'Expand row'"
              @click="onExpand(record, $event)"
            />
          </template>
          <!-- 集合展开：嵌套分页表 -->
          <template #expandedRowRender="{ record }">
            <CollectionDocTable
              v-if="record._type === 'col'"
              :collection-id="record.id"
              :keyword="keyword"
              @preview="openPreview"
              @changed="reloadAll"
            />
          </template>
          <template #emptyText><a-empty description="暂无文件集或独立文件" /></template>
        </a-table>
      </div>

      <!-- 分页（仅控制独立文件段）-->
      <div class="table-footer">
        <span class="toolbar-hint">独立文件共 {{ total }} 条</span>
        <a-pagination
          :current="page"
          :page-size="size"
          :total="total"
          :show-total="(t: number) => `共 ${t} 条`"
          :show-size-changer="true"
          :page-size-options="['10', '20', '50']"
          :show-quick-jumper="true"
          @change="onPage"
        />
      </div>
    </div>

    <!-- 预览抽屉 -->
    <a-drawer
      :open="previewDoc !== null"
      :width="640"
      destroy-on-close
      :title="previewDoc?.name || '文档预览'"
      @update:open="onPreviewOpenChange"
    >
      <DocPreview
        v-if="previewDoc"
        :doc-id="previewDoc.docId"
        :mime-type="previewDoc.mimeType"
        :doc-name="previewDoc.name"
        :source-location="previewDoc.sourceLocation"
        embedded
        @close="previewDoc = null"
      />
    </a-drawer>

    <!-- 文件集 新建/改名 Modal -->
    <a-modal
      :open="colModalOpen"
      :title="editingColId ? '编辑文件集' : '新建文件集'"
      ok-text="保存"
      cancel-text="取消"
      @update:open="(v: boolean) => (colModalOpen = v)"
      @ok="saveCol"
    >
      <a-form layout="vertical">
        <a-form-item label="名称"><a-input v-model:value="editingColName" placeholder="文件集名称" /></a-form-item>
        <a-form-item label="描述"><a-input v-model:value="editingColDesc" placeholder="可选" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- 归集弹窗 -->
    <CollectionPickerModal
      :open="pickerOpen"
      :doc-ids="pickerDocIds"
      :collections="collections"
      @update:open="(v: boolean) => (pickerOpen = v)"
      @assigned="onAssigned"
    />

    <!-- 上传文档 Modal -->
    <a-modal
      :open="uploadModalOpen"
      title="上传文档"
      :footer="null"
      :width="600"
      destroy-on-close
      @update:open="(v: boolean) => (uploadModalOpen = v)"
    >
      <div class="upload-dialog">
        <a-upload-dragger
          :before-upload="beforeUpload"
          :file-list="[]"
          :show-upload-list="false"
          accept=".pdf,.doc,.docx,.md,.txt,.html"
        >
          <div class="upload-inner">
            <InboxOutlined class="upload-ico" />
            <div class="upload-title">拖拽或点击选择文件</div>
            <div class="upload-hint">PDF · Word · Markdown · 纯文本</div>
          </div>
        </a-upload-dragger>

        <div class="route-row">
          <span class="route-label">分块路线</span>
          <a-radio-group v-model:value="plainTextRoute" button-style="solid" size="small">
            <a-radio-button :value="false">语义感知</a-radio-button>
            <a-radio-button :value="true">纯文本</a-radio-button>
          </a-radio-group>
        </div>
        <div class="route-desc">
          <template v-if="!plainTextRoute">保留标题/表格/段落结构，按语义边界切分（推荐）</template>
          <template v-else>忽略结构，按标题/段落边界切纯文本，不保留 block 元数据</template>
        </div>

        <Transition name="fade-up">
          <div v-if="picked" class="file-line">
            <FileTextOutlined class="file-ico" />
            <span class="file-name">{{ picked.name }}</span>
            <span class="file-size">{{ (picked.size / 1024).toFixed(1) }}KB</span>
            <a-button type="text" size="small" danger class="file-clear" @click="clearPick">
              <template #icon><DeleteOutlined /></template>
            </a-button>
          </div>
        </Transition>

        <a-button
          type="primary"
          :loading="uploading"
          :disabled="!picked"
          block
          class="upload-btn"
          @click="doUpload"
        >
          <template #icon><UploadOutlined /></template>
          {{ uploading ? '处理中…' : '开始入库' }}
        </a-button>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.doc-manage {
  gap: 0;
}

/* ── 上传 dialog 内容 ── */
.upload-dialog {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 4px;
}
.upload-dialog :deep(.ant-upload.ant-upload-drag) {
  background: var(--color-surface-secondary);
  border-color: var(--color-border);
}
.upload-inner {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 20px 0;
}
.upload-ico {
  font-size: 34px;
  color: var(--color-primary);
}
.upload-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-ink);
}
.upload-hint {
  font-size: 11px;
  color: var(--color-ink-tertiary);
}
.route-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.route-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
.route-desc {
  font-size: 12px;
  color: var(--color-ink-tertiary);
  line-height: 1.5;
  min-height: 32px;
}
.file-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--color-primary-light);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 12px;
}
.file-ico {
  color: var(--color-primary);
  flex-shrink: 0;
}
.file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-ink);
}
.file-size {
  color: var(--color-ink-tertiary);
  flex-shrink: 0;
}
.file-clear {
  flex-shrink: 0;
}
.upload-btn {
  margin-top: 2px;
}
.fade-up-enter-active,
.fade-up-leave-active {
  transition: all 0.2s ease;
}
.fade-up-enter-from,
.fade-up-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 0 8px;
}
.col-name {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  color: var(--color-ink);
}
.col-icon {
  color: var(--color-primary);
}
.col-count {
  color: var(--color-primary);
  font-size: 12px;
}
.doc-name {
  color: var(--color-ink);
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.doc-name:hover {
  color: var(--color-primary);
}
.doc-name-icon {
  color: var(--color-ink-tertiary);
  flex-shrink: 0;
}
/* 集合行隐藏勾选框（批量操作只对文档生效，集合行勾选无意义） */
:deep(.row-collection .ant-table-selection-column) {
  visibility: hidden;
}
:deep(.ant-table-expanded-row) {
  background: var(--color-surface-secondary) !important;
}
:deep(.ant-table-expanded-row .ant-table) {
  background: transparent;
}
</style>
