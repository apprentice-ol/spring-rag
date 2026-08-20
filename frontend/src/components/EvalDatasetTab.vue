<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  PlusOutlined,
  CloudDownloadOutlined,
  PlayCircleOutlined,
  EditOutlined,
  DeleteOutlined,
  QuestionCircleOutlined,
} from '@ant-design/icons-vue'
import {
  createDataset,
  updateDataset,
  deleteDataset,
  listItems,
  addItems,
  deleteItem,
  toggleItemEnabled,
  triggerRun,
  importLiveRag,
  type EvalDataset,
  type EvalItem,
} from '../api/eval'
import { parseDocIds, SOURCE_LABEL, CATEGORY_DESC, PARADIGMS, paradigmLabel } from './evalShared'
import { listCollections, type DocCollection } from '../api/collection'
import { useResizableColumns, vResize } from '../composables/useResizableColumns'

const props = defineProps<{ datasets: EvalDataset[] }>()
const emit = defineEmits<{ 'need-reload-datasets': [] }>()

const selectedDatasetId = ref<number | null>(null)
const items = ref<EvalItem[]>([])
const importing = ref(false)
const importSampleSize = ref(50)
const newQuestion = ref('')
const newExpected = ref('')
const newExpectedAnswer = ref('')

const dsColumns = useResizableColumns([
  { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '题数', dataIndex: 'itemCount', key: 'itemCount', width: 80 },
  { title: '操作', key: 'action', width: 170 },
])

const selectedDataset = computed(() => props.datasets.find((d) => d.id === selectedDatasetId.value) || null)

// 条目表分页（受控）：非受控 + 内联对象字面量时，任何重渲染都会重建 pagination 对象
// 把 pageSize 重置回初始值——size 选择器选了立即弹回，表现为"不能选择"
const itemPage = ref(1)
const itemPageSize = ref(10)
const itemPagination = computed(() => ({
  current: itemPage.value,
  pageSize: itemPageSize.value,
  showSizeChanger: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (t: number) => `共 ${t} 条`,
}))
function onItemTableChange(pag: { current?: number; pageSize?: number }) {
  itemPage.value = pag.current ?? 1
  itemPageSize.value = pag.pageSize ?? 10
}

watch(selectedDatasetId, async (id) => {
  itemPage.value = 1
  if (!id) {
    items.value = []
    return
  }
  try {
    items.value = await listItems(id)
  } catch {
    items.value = []
  }
})

function onRowClick(record: EvalDataset) {
  // toggle：点已选中行则折叠条目面板
  selectedDatasetId.value = selectedDatasetId.value === record.id ? null : record.id
}

// 新建 / 编辑
const createModalOpen = ref(false)
const editingName = ref('')
const editingDesc = ref('')
const editingId = ref<number | null>(null)

function openCreate() {
  editingId.value = null
  editingName.value = ''
  editingDesc.value = ''
  createModalOpen.value = true
}
function openEdit(d: EvalDataset) {
  editingId.value = d.id
  editingName.value = d.name
  editingDesc.value = d.description || ''
  createModalOpen.value = true
}
async function saveDataset() {
  if (!editingName.value.trim()) {
    message.warning('请输入名称')
    return
  }
  try {
    if (editingId.value) {
      await updateDataset(editingId.value, editingName.value.trim(), editingDesc.value)
      message.success('已更新')
    } else {
      const { id } = await createDataset(editingName.value.trim(), editingDesc.value)
      selectedDatasetId.value = id
      message.success('已创建')
    }
    createModalOpen.value = false
    emit('need-reload-datasets')
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '保存失败')
  }
}

function confirmDeleteDataset(d: EvalDataset) {
  Modal.confirm({
    title: '删除数据集',
    content: `确定删除「${d.name}」？含 ${d.itemCount} 条评测条目，不可恢复。`,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteDataset(d.id)
        if (selectedDatasetId.value === d.id) selectedDatasetId.value = null
        message.success('已删除')
        emit('need-reload-datasets')
      } catch (e: unknown) {
        message.error((e as ErrResp)?.response?.data?.message || '删除失败')
      }
    },
  })
}

async function doAddItem() {
  if (!selectedDatasetId.value) return
  if (!newQuestion.value.trim()) {
    message.warning('请输入问题')
    return
  }
  const expected = newExpected.value.split(/[,\n，]/).map((s) => s.trim()).filter(Boolean)
  try {
    await addItems(selectedDatasetId.value, [
      {
        question: newQuestion.value.trim(),
        expectedDocIds: expected,
        expectedAnswer: newExpectedAnswer.value.trim() || undefined,
      },
    ])
    message.success('已添加')
    newQuestion.value = ''
    newExpected.value = ''
    newExpectedAnswer.value = ''
    items.value = await listItems(selectedDatasetId.value)
    emit('need-reload-datasets')
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '添加失败')
  }
}

function confirmDeleteItem(it: EvalItem) {
  if (!selectedDatasetId.value) return
  Modal.confirm({
    title: '删除条目',
    content: '确定删除该评测条目？',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        await deleteItem(selectedDatasetId.value!, it.id)
        message.success('已删除')
        items.value = await listItems(selectedDatasetId.value!)
        emit('need-reload-datasets')
      } catch (e: unknown) {
        message.error((e as ErrResp)?.response?.data?.message || '删除失败')
      }
    },
  })
}

async function doToggle(it: EvalItem) {
  if (!selectedDatasetId.value) return
  const val = it.enabled === 1 ? 0 : 1
  try {
    await toggleItemEnabled(selectedDatasetId.value!, it.id, val)
    it.enabled = val
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '切换失败')
  }
}

/** 导入数据来源（目前仅 LiveRAG，select 形式预留多来源扩展） */
const IMPORT_SOURCES = [
  { value: 'liverag', label: 'LiveRAG 基准', desc: 'HuggingFace 实时 RAG 问答集（自动拉取）' },
]
const importModalOpen = ref(false)
const importSource = ref('liverag')
/** LiveRAG 导入目标数据集名：填已有则复用、填新名则自动创建 */
const importDatasetName = ref('LiveRAG')
/** 数据集名自动补全选项（已有数据集名） */
const datasetOptions = computed(() => props.datasets.map((d) => ({ value: d.name })))
/** auto-complete 过滤：输入匹配已有数据集名（忽略大小写） */
function filterDatasetOption(input: string, option: { value: string }): boolean {
  return option.value.toLowerCase().includes(input.toLowerCase())
}
/** 目标数据集实时提示：已存在（重复题跳过）/ 将新建 */
const targetHint = computed(() => {
  const name = importDatasetName.value.trim()
  if (!name) return ''
  const existing = props.datasets.find((d) => d.name === name)
  return existing
    ? `数据集「${name}」已存在（${existing.itemCount ?? 0} 题），重复题将自动跳过、新题追加`
    : `将创建新数据集「${name}」`
})

/** 语料归集：可选指定文件集；空 = 自动按数据集名查/建同名集合 */
const importCollectionId = ref<number | undefined>(undefined)
const collections = ref<DocCollection[]>([])
const collectionHint = computed(() => {
  if (importCollectionId.value != null) {
    const c = collections.value.find((x) => x.id === importCollectionId.value)
    return c ? `语料将归入已有文件集「${c.name}」（${c.docCount ?? 0} 篇）` : ''
  }
  const name = importDatasetName.value.trim() || 'LiveRAG'
  return `语料将归入同名文件集「${name}」（没有则自动创建）；评测跑批按期望文档归属隔离检索范围`
})

function openImport() {
  importModalOpen.value = true
  // 集合列表懒加载：首次打开拉一次（归集选择项）
  if (collections.value.length === 0) {
    listCollections()
      .then((cs) => (collections.value = cs))
      .catch(() => {})
  }
}
async function submitImport() {
  const name = importDatasetName.value.trim()
  if (!name) {
    message.warning('请填写目标数据集名称')
    return
  }
  if (importing.value) return
  importing.value = true
  try {
    const r = await importLiveRag({
      sampleSize: importSampleSize.value,
      datasetName: name,
      collectionId: importCollectionId.value,
    })
    message.success(`导入完成：${r.imported} 题新导入，${r.skipped} 题跳过，语料已归入文件集「${r.collectionName}」`)
    emit('need-reload-datasets')
    selectedDatasetId.value = r.datasetId
    importModalOpen.value = false
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || 'LiveRAG 导入失败')
  } finally {
    importing.value = false
  }
}

const triggerCategory = ref<string | undefined>(undefined)
const triggerLimit = ref<number | undefined>(undefined)
const triggerRewrite = ref(false)
const triggerParadigm = ref('naive')
const triggerAnswerEval = ref(false)
const triggerPerQuestion = ref(false)

/** 当前数据集条目的分类选项（去重，用于触发时筛选） */
const categoryOptions = computed(() => {
  const s = new Set<string>()
  for (const it of items.value) if (it.category) s.add(it.category)
  return Array.from(s)
})

async function doTrigger() {
  if (!selectedDatasetId.value) {
    message.warning('请先选择数据集')
    return
  }
  try {
    const opts: {
      category?: string
      limit?: number
      rewriteEnabled?: boolean
      paradigm?: string
      answerEval?: boolean
      perQuestion?: boolean
    } = {}
    if (triggerCategory.value) opts.category = triggerCategory.value
    if (triggerLimit.value && triggerLimit.value > 0) opts.limit = triggerLimit.value
    if (triggerRewrite.value) opts.rewriteEnabled = true
    if (triggerAnswerEval.value) opts.answerEval = true
    if (triggerPerQuestion.value) opts.perQuestion = true
    opts.paradigm = triggerParadigm.value
    const { runId } = await triggerRun(selectedDatasetId.value, opts)
    const scope =
      [
        `范式 ${paradigmLabel(triggerParadigm.value)}`,
        triggerCategory.value ? `分类「${triggerCategory.value}」` : null,
        triggerLimit.value ? `抽样 ${triggerLimit.value} 条` : null,
        triggerRewrite.value ? '含改写' : '裸检索',
        triggerAnswerEval.value ? '答案评测' : null,
        triggerPerQuestion.value ? '限定期望文档' : null,
      ]
        .filter(Boolean)
        .join(' · ') || '全量'
    message.success(`已触发运行 #${runId}（${scope}），到「运行记录」查看进度`)
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '触发失败')
  }
}

const itemColumns = useResizableColumns([
  { title: '问题', dataIndex: 'question', key: 'question', ellipsis: true },
  { title: '标准答案', dataIndex: 'expectedAnswer', key: 'expectedAnswer', width: 200, ellipsis: true },
  { title: '分类', dataIndex: 'category', key: 'category', width: 100 },
  { title: '来源', dataIndex: 'source', key: 'source', width: 130 },
  { title: '期望文档', dataIndex: 'expected', key: 'expected', width: 90 },
  { title: '启用', dataIndex: 'enabled', key: 'enabled', width: 70 },
  { title: '操作', key: 'action', width: 90 },
])

const itemSummary = computed(() => {
  const bySource = new Map<string, number>()
  for (const it of items.value) {
    const src = it.source || 'builtin'
    bySource.set(src, (bySource.get(src) ?? 0) + 1)
  }
  return Array.from(bySource.entries()).sort((a, b) => b[1] - a[1])
})

interface ErrResp {
  response?: { data?: { message?: string } }
}
</script>

<template>
  <div class="ds-tab">
    <!-- 页头：标题 + 主操作（导入 / 新建） -->
    <div class="page-header">
      <div class="page-header-text">
        <h1 class="page-title">数据集</h1>
        <p class="page-desc">黄金集题库 · 点击数据集行展开，管理评测条目并触发运行</p>
      </div>
      <div class="page-actions">
        <a-button @click="openImport">
          <template #icon><CloudDownloadOutlined /></template>导入数据集
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>新建数据集
        </a-button>
      </div>
    </div>

    <!-- 数据集表格卡 -->
    <div class="table-card">
      <div class="table-toolbar">
        <span class="toolbar-hint">共 {{ datasets.length }} 个数据集 · 点击行展开条目</span>
      </div>
      <a-table
        :data-source="datasets"
        :columns="dsColumns"
        size="small"
        row-key="id"
        :pagination="false"
        :scroll="{ x: 760 }"
        :row-class-name="(r: any) => (r.id === selectedDatasetId ? 'row-selected' : '')"
        :custom-row="(r: any) => ({ onClick: () => onRowClick(r) })"
      >
        <template #headerCell="{ column }">
          <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="dsColumns">{{ column.title }}</span>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-button type="link" size="small" @click.stop="openEdit(record)"><EditOutlined />编辑</a-button>
            <a-button type="link" size="small" danger @click.stop="confirmDeleteDataset(record)"><DeleteOutlined />删除</a-button>
          </template>
        </template>
        <template #emptyText><a-empty description="暂无数据集，请新建或导入" /></template>
      </a-table>
    </div>

    <!-- 选中数据集：触发条件筛选卡 + 条目表格卡 -->
    <template v-if="selectedDataset">
      <!-- 评测范围 + 触发（筛选卡形式，选中数据集即可见） -->
      <div class="filter-card">
        <div class="filter-row">
          <div class="filter-item">
            <span class="filter-label">范式</span>
            <a-select v-model:value="triggerParadigm" style="width: 180px">
              <a-select-option v-for="p in PARADIGMS" :key="p.value" :value="p.value">
                {{ p.label }} · {{ p.desc }}
              </a-select-option>
            </a-select>
          </div>
          <div class="filter-item">
            <span class="filter-label">评测范围</span>
            <a-select
              v-model:value="triggerCategory"
              placeholder="全部分类"
              allow-clear
              style="width: 140px"
              :options="categoryOptions.map((c) => ({ label: c, value: c }))"
            />
          </div>
          <div class="filter-item">
            <span class="filter-label">抽样</span>
            <a-input-number v-model:value="triggerLimit" :min="1" placeholder="数量" style="width: 110px" />
          </div>
          <span class="filter-hint">不选 = 全量（{{ items.length }} 题）</span>
          <div class="filter-actions">
            <a-checkbox v-model:checked="triggerRewrite" title="勾选后评测走真实聊天链路（含 LLM 改写，分数更接近线上）">启用查询改写</a-checkbox>
            <a-checkbox v-model:checked="triggerAnswerEval" title="勾选后对每题生成答案，并用 LLM-as-judge 打「答案正确性/忠实度」分（每题多 2 次 LLM 调用）">答案评测</a-checkbox>
            <a-checkbox v-model:checked="triggerPerQuestion" title="实验开关：检索限定在该题期望文档内，模拟官方独立语料（验证混合语料是否唯一瓶颈）">仅检索期望文档</a-checkbox>
            <a-button type="primary" :disabled="!items.length" @click="doTrigger">
              <template #icon><PlayCircleOutlined /></template>触发评测
            </a-button>
          </div>
        </div>
      </div>

      <!-- 条目表格卡：工具栏（数据集名 + 来源统计）+ 追加表单 + 条目表 -->
      <div class="table-card">
        <div class="table-toolbar">
          <div class="toolbar-left">
            <span class="panel-name">{{ selectedDataset.name }}</span>
            <span class="selected-count">{{ items.length }} 条</span>
            <span v-for="[src, n] in itemSummary" :key="src" class="toolbar-hint">{{ SOURCE_LABEL[src] || src }} × {{ n }}</span>
          </div>
          <div class="toolbar-right">
            <a-button type="text" size="small" @click="selectedDatasetId = null">收起</a-button>
          </div>
        </div>

        <!-- 追加评测条目 -->
        <div class="add-row">
          <a-textarea v-model:value="newQuestion" placeholder="用户问题" :auto-size="{ minRows: 1, maxRows: 3 }" style="flex: 2" />
          <a-input v-model:value="newExpected" placeholder="期望 doc_id（逗号或换行分隔）" style="flex: 2" />
          <a-input v-model:value="newExpectedAnswer" placeholder="标准答案（答案评测用，可选）" style="flex: 3" />
          <a-button type="primary" @click="doAddItem">
            <template #icon><PlusOutlined /></template>添加
          </a-button>
        </div>

        <a-table
          :data-source="items"
          :columns="itemColumns"
          size="small"
          row-key="id"
          :pagination="itemPagination"
          @change="onItemTableChange"
          :scroll="{ x: 760 }"
        >
          <template #headerCell="{ column }">
            <span v-if="column.key === 'category'" class="th-cell" v-resize:[column.key]="itemColumns">
              分类
              <a-tooltip placement="top" :overlay-style="{ maxWidth: '340px' }">
                <template #title>
                  <div class="cat-tip-title">LiveRAG 问题类型（answer-type）</div>
                  <div v-for="c in CATEGORY_DESC" :key="c.key" class="cat-tip-row">
                    <b>{{ c.key }}</b> {{ c.desc }}
                  </div>
                </template>
                <QuestionCircleOutlined class="col-help" />
              </a-tooltip>
            </span>
            <span v-else-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="itemColumns">{{ column.title }}</span>
          </template>
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'expected'">{{ parseDocIds(record.expectedDocIds).length }}</template>
            <template v-else-if="column.key === 'expectedAnswer'">
              <a-tooltip v-if="record.expectedAnswer" :title="record.expectedAnswer">
                <span class="ans-cell">{{ record.expectedAnswer }}</span>
              </a-tooltip>
              <span v-else class="muted">-</span>
            </template>
            <template v-else-if="column.key === 'source'">{{ SOURCE_LABEL[record.source] || record.source }}</template>
            <template v-else-if="column.key === 'enabled'">
              <a-switch :checked="record.enabled === 1" size="small" @change="doToggle(record)" />
            </template>
            <template v-else-if="column.key === 'action'">
              <a-button type="link" size="small" danger @click="confirmDeleteItem(record)">删除</a-button>
            </template>
          </template>
        </a-table>
      </div>
    </template>
    <div v-else class="select-hint">点击上方数据集查看与管理其评测条目</div>

    <!-- 新建/编辑 Modal -->
    <a-modal
      :open="createModalOpen"
      :title="editingId ? '编辑数据集' : '新建数据集'"
      ok-text="保存"
      cancel-text="取消"
      @update:open="(v: boolean) => (createModalOpen = v)"
      @ok="saveDataset"
    >
      <a-form layout="vertical">
        <a-form-item label="名称"><a-input v-model:value="editingName" /></a-form-item>
        <a-form-item label="描述"><a-input v-model:value="editingDesc" /></a-form-item>
      </a-form>
    </a-modal>

    <!-- 导入数据集 Modal -->
    <a-modal
      :open="importModalOpen"
      title="导入数据集"
      :confirm-loading="importing"
      ok-text="开始导入"
      cancel-text="取消"
      destroy-on-close
      @update:open="(v: boolean) => (importModalOpen = v)"
      @ok="submitImport"
    >
      <a-form layout="vertical" class="import-form">
        <a-form-item label="数据来源">
          <a-select v-model:value="importSource">
            <a-select-option v-for="s in IMPORT_SOURCES" :key="s.value" :value="s.value">
              {{ s.label }}<span class="opt-desc">{{ s.desc }}</span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="导入条数">
          <a-input-number v-model:value="importSampleSize" :min="1" :max="895" style="width: 100%" />
        </a-form-item>
        <a-form-item label="导入到数据集">
          <a-auto-complete
            v-model:value="importDatasetName"
            :options="datasetOptions"
            :filter-option="filterDatasetOption"
            placeholder="选已有数据集，或输入新名（自动创建）"
            allow-clear
          />
          <div class="target-hint">{{ targetHint || '输入或选择目标数据集名称' }}</div>
        </a-form-item>
        <a-form-item label="语料归入文件集">
          <a-select
            v-model:value="importCollectionId"
            placeholder="自动：与数据集同名创建/复用"
            allow-clear
          >
            <a-select-option v-for="c in collections" :key="c.id" :value="c.id">
              {{ c.name }}<span class="opt-desc">{{ c.docCount ?? 0 }} 篇</span>
            </a-select-option>
          </a-select>
          <div class="target-hint">{{ collectionHint }}</div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.ds-tab {
  display: flex;
  flex-direction: column;
}
/* 页头/筛选卡自带下边距，这里给表格卡之间补 12px 节奏 */
.ds-tab .table-card {
  margin-bottom: 12px;
}
/* 导入 dialog */
.import-form .opt-desc {
  margin-left: 6px;
  font-size: 11px;
  color: var(--color-ink-tertiary);
}
.target-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
  min-height: 18px;
}
:deep(.row-selected) {
  background: var(--color-primary-light) !important;
}
/* 分类列表头问号 tooltip */
.col-help {
  margin-left: 4px;
  font-size: 12px;
  color: var(--color-ink-tertiary);
  cursor: help;
}
.cat-tip-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.cat-tip-row {
  font-size: 12px;
  line-height: 1.7;
}
/* 条目工具栏里的数据集名 */
.panel-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-ink);
}
/* 触发条件提示文案 */
.filter-hint {
  font-size: 12px;
  color: var(--color-ink-tertiary);
}
/* 追加条目表单行（表格卡内、条目表上方） */
.add-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
  flex-wrap: wrap;
  padding: 12px 20px 4px;
}
.select-hint {
  text-align: center;
  padding: 24px;
  color: var(--color-ink-tertiary);
  font-size: 13px;
}
.ans-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-ink-secondary);
}
.muted {
  color: var(--color-ink-tertiary);
}
</style>
