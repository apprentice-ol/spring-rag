<script setup lang="ts">
import {ref, watch, onMounted} from 'vue'
import {message, Modal} from 'ant-design-vue'
import {EyeOutlined, MinusCircleOutlined} from '@ant-design/icons-vue'
import {pageDocuments, type DocumentInfo} from '../api/upload'
import {assignDocs} from '../api/collection'
import {useResizableColumns, vResize} from '../composables/useResizableColumns'

/** 集合展开后的嵌套文档表（独立分页，列宽可拖拽）。 */
const props = defineProps<{ collectionId: number; keyword?: string }>()
const emit = defineEmits<{ preview: [doc: DocumentInfo]; changed: [] }>()

const rows = ref<DocumentInfo[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await pageDocuments({
      page: page.value,
      size: size.value,
      collectionId: props.collectionId,
      keyword: props.keyword?.trim() || undefined,
    })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(
    () => props.collectionId,
    () => {
      page.value = 1
      load()
    },
)
// 父组件搜索关键词变化：重置到第 1 页并按关键词过滤（搜索自动展开场景）
watch(
    () => props.keyword,
    () => {
      page.value = 1
      load()
    },
)

function onPage(p: number, s: number) {
  page.value = p
  size.value = s
  load()
}

function removeFromCollection(doc: DocumentInfo) {
  Modal.confirm({
    title: '移出集合',
    content: `将「${doc.name}」移出该集合？文档与向量不会被删除，变为独立文件。`,
    okText: '移出',
    cancelText: '取消',
    onOk: async () => {
      try {
        await assignDocs(null, [doc.docId])
        message.success('已移出')
        if (rows.value.length === 1 && page.value > 1) page.value -= 1
        await load()
        emit('changed')
      } catch (e: unknown) {
        message.error((e as ErrResp)?.response?.data?.message || '移出失败')
      }
    },
  })
}

interface ErrResp {
  response?: { data?: { message?: string } }
}

const columns = useResizableColumns([
  {title: '文档', dataIndex: 'name', key: 'name', width: 140, ellipsis: true},
  {title: '类型', dataIndex: 'mimeType', key: 'mimeType', width: 140, ellipsis: true},
  {title: '向量块', dataIndex: 'chunkCount', key: 'chunkCount', width: 70},
  {title: '状态', dataIndex: 'status', key: 'status', width: 80},
  {title: '操作', key: 'action', width: 140},
])

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
</script>

<template>
  <div class="col-doc-table">
    <a-table
        :data-source="rows"
        :columns="columns"
        :loading="loading"
        size="small"
        row-key="docId"
        :pagination="{
        current: page,
        pageSize: size,
        total,
        onChange: onPage,
        showSizeChanger: true,
        pageSizeOptions: ['10', '20', '50'],
        showTotal: (t: number) => `共 ${t} 个`,
      }"
        :scroll="{ x: 620 }"
    >
      <template #headerCell="{ column }">
        <span v-if="typeof column.title === 'string' && !column.sorter" class="th-cell" v-resize:[column.key]="columns">{{ column.title }}</span>
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a class="doc-name" @click="emit('preview', record)">{{ record.name }}</a>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="STATUS_COLOR[record.status] || 'default'">{{
              STATUS_TEXT[record.status] || record.status
            }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button type="link" size="small" @click="emit('preview', record)">
            <EyeOutlined/>
            预览
          </a-button>
          <a-button type="link" size="small" danger @click="removeFromCollection(record)">
            <MinusCircleOutlined/>
            移出
          </a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>

<style scoped>
.col-doc-table {
  padding: 4px 0;
}

.doc-name {
  color: var(--color-ink);
  cursor: pointer;
}

.doc-name:hover {
  color: var(--color-primary);
}
</style>
