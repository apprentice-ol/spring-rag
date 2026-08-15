<script setup lang="ts">
import { ref, watch, computed, onMounted } from 'vue'
import {
  LoadingOutlined, FilePdfOutlined, FileImageOutlined,
  FileMarkdownOutlined, DownloadOutlined, ExclamationCircleOutlined, FileOutlined,
} from '@ant-design/icons-vue'
import { http } from '../api/client'
import type { DocumentInfo } from '../api/upload'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

const md: MarkdownIt = new MarkdownIt({
  html: true, linkify: true, breaks: true,
  highlight(str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try { return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang, ignoreIllegals: true }).value}</code></pre>` } catch { /* fall through */ }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  },
})

const props = defineProps<{
  docId: string
  mimeType?: string | null
  docName?: string | null
  sourceLocation?: string | null
  embedded?: boolean
}>()
const emit = defineEmits<{ close: [] }>()

// 全屏预览页缺失 props → 自动拉取
const remoteMime = ref<string | null>(null)
const remoteName = ref<string | null>(null)
const remoteSource = ref<string | null>(null)

onMounted(async () => {
  if (props.mimeType || props.docName) return
  try {
    const { data } = await http.get<DocumentInfo>(`/docs/${props.docId}`)
    remoteMime.value = data.mimeType
    remoteName.value = data.name
    remoteSource.value = data.sourceLocation
  } catch { /* 忽略，回退 */ }
})

const mimeType = computed(() => props.mimeType ?? remoteMime.value)
const docName = computed(() => props.docName ?? remoteName.value ?? '文档预览')
const sourceUrl = computed(() => props.sourceLocation ?? remoteSource.value ?? '')

/**
 * 后端代理 URL（仅 markdown/纯文本 fetch 用）：避开浏览器直连 RustFS 的 CORS 限制
 * （md/txt fetch 直连 localhost:9000 会被拦截）。PDF/图片/下载已改直连 sourceUrl
 * （kb 桶公共读，iframe/img 不受 CORS，见 FileStorageServiceImpl#ensureAssetBucketPublic 注释）。
 */
const proxyFileUrl = computed(() =>
  sourceUrl.value ? `/api/rag/docs/${props.docId}/file` : ''
)

const isPdf = computed(() => (mimeType.value || '').toLowerCase().includes('pdf'))
const isImage = computed(() => (mimeType.value || '').toLowerCase().startsWith('image/'))
const isMarkdown = computed(() => {
  const m = (mimeType.value || '').toLowerCase()
  return m.includes('markdown') || m.includes('text/x-markdown')
})
const isPlainText = computed(() => {
  const m = (mimeType.value || '').toLowerCase()
  return m.startsWith('text/') && !isMarkdown.value
})

// ── 文本类 fetch ──
const textContent = ref('')
const textLoading = ref(false)
const textError = ref(false)
const fetched = ref(false)

watch([() => props.docId, sourceUrl], async () => {
  textContent.value = ''; textError.value = false; fetched.value = false
  if (!isMarkdown.value && !isPlainText.value) return
  if (!sourceUrl.value) { textError.value = true; return }
  textLoading.value = true
  try {
    const resp = await fetch(proxyFileUrl.value)
    if (!resp.ok) throw new Error(`${resp.status}`)
    textContent.value = await resp.text()
  } catch {
    textError.value = true
    // 源文件拉取失败（后端代理 /file 异常 / 对象存储不可达）→ 自动 fallback 全段分段预览，
    // 不再让用户手动点「尝试加载分段」
    loadChunks()
  } finally {
    textLoading.value = false; fetched.value = true
  }
}, { immediate: true })

function renderMd(text: string): string {
  return md.render(text).replace(
    /src="http:\/\/localhost:9000\/springai-rag-assets\//g,
    'src="/api/rag/docs/assets/'
  )
}

const renderedHtml = computed(() => textContent.value ? renderMd(textContent.value) : '')

// ── 没有源文件时的 chunk fallback ──
const chunksLoading = ref(false)
const chunksHtml = ref('')

async function loadChunks() {
  chunksLoading.value = true
  try {
    const { data } = await http.get<any>(`/docs/${props.docId}/content`)
    const chunks: any[] = data.chunks || []
    // 先把所有分块正文拼成完整文档再整体 md.render 一次：逐块单独渲染会切断跨块的
    // 代码块 / 表格 / 列表结构（产生未闭合 HTML，排版错乱）；用 --- 分隔保留分段视觉
    const fullText = chunks.map((c: any) => (c.content || '').trim()).filter(Boolean).join('\n\n---\n\n')
    chunksHtml.value = fullText ? renderMd(fullText) : ''
  } catch {
    chunksHtml.value = ''
  } finally {
    chunksLoading.value = false
  }
}

const noSource = computed(() => !sourceUrl.value && !isPdf.value && !isImage.value && !chunksLoading.value)

function extLabel() {
  const name = (docName.value || '').toLowerCase()
  if (name.endsWith('.pdf')) return 'PDF'
  if (name.endsWith('.md')) return 'Markdown'
  if (name.endsWith('.txt')) return '纯文本'
  if (name.endsWith('.doc') || name.endsWith('.docx')) return 'Word'
  if (name.endsWith('.xls') || name.endsWith('.xlsx')) return 'Excel'
  return ''
}
</script>

<template>
  <div class="preview" :class="{ 'preview-fullpage': !embedded }">
    <!-- 头部 -->
    <div class="preview-header">
      <div class="preview-title-row">
        <FilePdfOutlined v-if="isPdf" class="preview-icon" />
        <FileImageOutlined v-else-if="isImage" class="preview-icon" />
        <FileMarkdownOutlined v-else-if="isMarkdown" class="preview-icon" />
        <FileOutlined v-else class="preview-icon" />
        <div class="preview-title-text">
          <h3 class="preview-name">{{ docName }}</h3>
          <span class="preview-meta">
            <span v-if="extLabel()" class="doc-badge">{{ extLabel() }}</span>
            {{ mimeType || '' }}
            <span v-if="!sourceUrl" class="no-source-badge">无源文件</span>
          </span>
        </div>
      </div>
      <div class="preview-actions">
        <a-button v-if="sourceUrl" type="text" size="small" class="preview-dl" :href="sourceUrl" :download="docName">
          <template #icon><DownloadOutlined /></template>
        </a-button>
        <a-button v-if="embedded" type="text" size="small" class="preview-close" @click="emit('close')">✕</a-button>
      </div>
    </div>

    <!-- PDF: iframe 走后端代理（避开 CORS / 认证） -->
    <iframe
      v-if="isPdf && sourceUrl"
      class="preview-iframe"
      :src="sourceUrl"
      :title="docName"
    />

    <!-- 图片: img 走后端代理 -->
    <div v-else-if="isImage && sourceUrl" class="preview-image-wrap">
      <img class="preview-image" :src="proxyFileUrl" :alt="docName" />
    </div>

    <!-- Markdown / 纯文本: fetch 原始文件 -->
    <div v-else-if="isMarkdown || isPlainText" class="preview-body scrollable">
      <div v-if="textLoading || chunksLoading" class="preview-loading">
        <LoadingOutlined spin style="font-size:24px" /><p>加载中…</p>
      </div>
      <div v-else-if="textError && !chunksHtml" class="preview-error">
        <div class="error-box">
          <ExclamationCircleOutlined /> 无法加载源文件
          <a-button type="link" size="small" @click="loadChunks">尝试加载分段</a-button>
        </div>
      </div>
      <!-- 优先源文件渲染 -->
      <div v-else-if="fetched && renderedHtml" class="preview-container markdown-body" v-html="renderedHtml" />
      <!-- fallback: chunk 文本 -->
      <div v-else-if="chunksHtml" class="preview-container markdown-body" v-html="chunksHtml" />
      <div v-else-if="!textLoading" class="preview-empty-hint">
        <a-button type="link" @click="loadChunks">加载分段文本</a-button>
      </div>
    </div>

    <!-- chunk 渲染（须在「无源文件 PDF/图片」分支之前：加载分段后命中此分支展示，否则被下方 loading 分支永久遮挡） -->
    <div v-else-if="chunksHtml" class="preview-body scrollable">
      <div class="preview-container markdown-body" v-html="chunksHtml" />
    </div>

    <!-- 无源文件: PDF/图片提示 + 触发分段加载 -->
    <div v-else-if="(isPdf || isImage) && !sourceUrl" class="preview-loading">
      <FileOutlined style="font-size: 40px; opacity: 0.2; margin-bottom: 12px" />
      <p>该文档没有保存源文件</p>
      <a-button type="link" size="small" @click="loadChunks">查看分段文本</a-button>
    </div>

    <!-- 无源文件 + 无 chunk -->
    <div v-else class="preview-loading">
      <FileOutlined style="font-size: 40px; opacity: 0.2; margin-bottom: 12px" />
      <p>暂无可预览的内容</p>
      <span class="hint-text">（改之前入库的文档没有源文件，需重新上传）</span>
    </div>
  </div>
</template>

<style scoped>
.preview { height: 100%; display: flex; flex-direction: column; background: var(--color-bg); }
.preview-iframe { width: 100%; flex: 1; border: 0; }
.preview-image-wrap { flex: 1; display: flex; align-items: center; justify-content: center; overflow: auto; background: var(--color-surface-secondary); padding: 16px; }
.preview-image { max-width: 100%; max-height: 100%; object-fit: contain; }
.preview-header {
  display: flex; justify-content: space-between; align-items: flex-start;
  padding: 16px 20px 12px; border-bottom: 1px solid var(--color-border-light); flex-shrink: 0;
}
.preview-title-row { display: flex; align-items: flex-start; gap: 10px; min-width: 0; }
.preview-icon { font-size: 20px; color: var(--color-primary); margin-top: 2px; flex-shrink: 0; }
.preview-title-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.preview-name { margin: 0; font-size: 15px; font-weight: 600; color: var(--color-ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-meta { font-size: 12px; color: var(--color-ink-tertiary); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.doc-badge { display: inline-block; padding: 0 6px; font-size: 10px; font-weight: 600; letter-spacing: 0.04em; background: var(--color-surface-secondary); border: 1px solid var(--color-border-light); border-radius: var(--radius-sm); color: var(--color-ink-secondary); }
.no-source-badge { color: var(--color-signal); font-weight: 500; }
.preview-actions { display: flex; gap: 4px; flex-shrink: 0; }
.preview-dl { color: var(--color-ink-secondary); }
.preview-close { color: var(--color-ink-secondary); font-size: 14px; }
.preview-body { flex: 1; overflow-y: auto; display: flex; justify-content: center; }
.scrollable { overflow-y: auto; }
.preview-loading, .preview-error, .preview-empty-hint {
  flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center;
  color: var(--color-ink-tertiary); gap: 8px;
}
.error-box { display: flex; align-items: center; gap: 8px; color: var(--color-ink-secondary); }
.preview-container { width: 100%; max-width: 880px; margin: 0 auto; padding: 20px 24px 40px; font-size: 14px; line-height: 1.75; color: var(--color-ink); }
/* github-markdown-css 自带白色背景，与页面灰绿背景分层 → 统一透明继承页面背景 */
.preview-container.markdown-body { background: transparent; }
/* 全屏预览页：内容宽度跟随可拖拽容器（自由拉宽缩短） */
.preview-fullpage .preview-container { max-width: none; }
.preview-container :deep(pre) { border-radius: var(--radius-sm); }
.preview-container :deep(table) { display: block; overflow-x: auto; }
.preview-container :deep(img) { max-width: 100%; }
.preview-container :deep(hr) { margin: 24px 0; border: none; border-top: 1px dashed var(--color-border-light); }
.hint-text { font-size: 12px; opacity: 0.6; }

@media (max-width: 768px) {
  .preview-header { padding: 12px 14px 10px; }
  .preview-name { font-size: 14px; }
  .preview-container { padding: 16px 14px 32px; font-size: 13.5px; }
  .preview-dl, .preview-close { font-size: 16px; }
}
</style>
