<script setup lang="ts">
import {
  FileTextOutlined,
  ExportOutlined,
  DeleteOutlined,
  CheckCircleOutlined,
  ExclamationCircleOutlined,
  CloseCircleOutlined,
} from '@ant-design/icons-vue'
import type { DocumentInfo } from '../api/upload'

/** 单个文档项：集合内文档与独立文件列表共用。 */
defineProps<{ doc: DocumentInfo }>()
const emit = defineEmits<{
  preview: [doc: DocumentInfo]
  openTab: [doc: DocumentInfo]
  delete: [doc: DocumentInfo]
}>()
</script>

<template>
  <div class="doc-item" :class="{ 'doc-failed': doc.status === 'FAILED' }" @click="emit('preview', doc)">
    <div class="doc-left">
      <FileTextOutlined class="doc-icon" />
      <div class="doc-info">
        <span class="doc-name">{{ doc.name }}</span>
        <span class="doc-meta">
          <CheckCircleOutlined v-if="doc.status === 'DONE'" class="status-icon status-done" />
          <ExclamationCircleOutlined v-else-if="doc.status === 'FAILED'" class="status-icon status-failed" />
          <CloseCircleOutlined v-else class="status-icon status-other" />
          {{ doc.status === 'DONE' ? `${doc.chunkCount} 段` : doc.status }}
        </span>
      </div>
    </div>
    <div class="doc-actions">
      <a-button type="text" size="small" class="doc-newtab" title="新标签页打开" @click.stop="emit('openTab', doc)">
        <template #icon><ExportOutlined /></template>
      </a-button>
      <a-button type="text" size="small" danger class="doc-delete" @click.stop="emit('delete', doc)">
        <template #icon><DeleteOutlined /></template>
      </a-button>
    </div>
  </div>
</template>

<style scoped>
.doc-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  transition: background 0.15s;
  cursor: pointer;
}
.doc-item:hover {
  background: var(--color-surface-secondary);
}
.doc-failed {
  background: rgba(220, 38, 38, 0.03);
}
.doc-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.doc-icon {
  font-size: 14px;
  color: var(--color-ink-tertiary);
  flex-shrink: 0;
}
.doc-info {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  flex: 1;
}
.doc-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-meta {
  font-size: 11px;
  color: var(--color-ink-tertiary);
  display: flex;
  align-items: center;
  gap: 4px;
}
.status-icon {
  font-size: 11px;
}
.status-done {
  color: #22c55e;
}
.status-failed {
  color: #ef4444;
}
.status-other {
  color: #f59e0b;
}
.doc-actions {
  display: flex;
  gap: 0;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s;
}
.doc-item:hover .doc-actions {
  opacity: 1;
}
.doc-newtab {
  color: var(--color-ink-tertiary);
}
.doc-newtab:hover {
  color: var(--color-primary);
}

@media (max-width: 768px) {
  /* 移动端没有 hover：操作按钮常显 */
  .doc-actions {
    opacity: 1;
  }
}
</style>
