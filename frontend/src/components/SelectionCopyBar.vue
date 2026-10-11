<script setup lang="ts">
/**
 * 划词复制浮条（全局挂载）：选中文本落在正文（.markdown-body——对话回答 / 文档预览）内时，
 * 在选区上方浮出「复制选中 N 字」。
 *
 * 一条回答可能很长，常只要其中一段——整条复制口径不对，Ctrl+C 又要求用户
 * 先确认选区没跑偏。浮条直接回答「我选中了什么（N 字）」并一键复制。
 * 配全局 ::selection（主色反白），选中范围本身即高亮可见。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { CopyOutlined } from '@ant-design/icons-vue'
import { copyWithToast } from '../composables/useClipboard'

const visible = ref(false)
const x = ref(0)
const y = ref(0)
const count = ref(0)
/** selectionchange 后用户仍可能微调选区，复制时用快照而不是现场再读 */
let snapshot = ''

function hide() {
  visible.value = false
  snapshot = ''
}

function update() {
  const sel = window.getSelection()
  if (!sel || sel.isCollapsed || sel.rangeCount === 0) {
    hide()
    return
  }
  const range = sel.getRangeAt(0)
  const node = range.commonAncestorContainer
  const el = node.nodeType === 1 ? node as HTMLElement : node.parentElement
  // 只在正文划词时出现；输入框 / 表格 / 菜单里的选择不打扰
  if (!el?.closest('.markdown-body')) {
    hide()
    return
  }
  const text = sel.toString()
  if (!text.trim()) {
    hide()
    return
  }
  snapshot = text
  count.value = text.length
  const rect = range.getBoundingClientRect()
  x.value = Math.max(70, Math.min(rect.left + rect.width / 2, window.innerWidth - 80))
  y.value = Math.max(8, rect.top - 40)
  visible.value = true
}

function copy() {
  if (snapshot) {
    copyWithToast(snapshot, `选中内容（${count.value} 字）`)
  }
  window.getSelection()?.removeAllRanges()
  hide()
}

onMounted(() => document.addEventListener('selectionchange', update))
onUnmounted(() => document.removeEventListener('selectionchange', update))
</script>

<template>
  <Teleport to="body">
    <!-- mousedown.prevent：不抢焦点、不清选区（清了复制时 selection 就空了） -->
    <div
      v-if="visible"
      class="sel-copy-bar"
      :style="{ left: `${x}px`, top: `${y}px` }"
      title="复制选中的文本"
      @mousedown.prevent
      @click="copy"
    >
      <CopyOutlined /><span>复制选中</span><span class="num">{{ count }} 字</span>
    </div>
  </Teleport>
</template>

<style scoped>
.sel-copy-bar {
  position: fixed; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 11px; font-size: 12px; z-index: 2000;
  background: var(--color-surface); color: var(--color-ink);
  border: 1px solid var(--color-border); border-radius: var(--radius-md);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.14);
  cursor: pointer; user-select: none;
}
.sel-copy-bar .num { color: var(--color-primary); font-variant-numeric: tabular-nums; }
.sel-copy-bar:hover { color: var(--color-primary); border-color: var(--color-primary); }
</style>
