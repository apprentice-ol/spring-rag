<script setup lang="ts">
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { FolderOutlined, ExportOutlined } from '@ant-design/icons-vue'
import { assignDocs, type DocCollection } from '../api/collection'

/** 归集/移动/移出弹窗：选择目标集合或「移出」。批量与单文档共用。 */
const props = defineProps<{ open: boolean; docIds: string[]; collections: DocCollection[] }>()
const emit = defineEmits<{ 'update:open': [boolean]; assigned: [] }>()

// 'remove' = 移出集合；否则为集合 id
const selected = ref<string | number>('')
const submitting = ref(false)

watch(
  () => props.open,
  (op) => {
    if (op) selected.value = props.collections.length ? props.collections[0].id : 'remove'
  },
)

async function confirm() {
  const cid = selected.value === 'remove' ? null : Number(selected.value)
  submitting.value = true
  try {
    const r = await assignDocs(cid, props.docIds)
    message.success(`已${cid == null ? '移出集合' : '移动到目标集合'} ${r.updated} 个文档`)
    emit('assigned')
    emit('update:open', false)
  } catch (e: unknown) {
    message.error((e as ErrResp)?.response?.data?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

interface ErrResp {
  response?: { data?: { message?: string } }
}
</script>

<template>
  <a-modal
    :open="open"
    :title="`移动 ${docIds.length} 个文档到集合`"
    ok-text="确定"
    cancel-text="取消"
    :confirm-loading="submitting"
    @update:open="(v: boolean) => emit('update:open', v)"
    @ok="confirm"
  >
    <a-radio-group v-model:value="selected" class="picker">
      <a-radio v-for="c in collections" :key="c.id" :value="c.id" class="pick-item">
        <FolderOutlined class="pick-icon" />
        <span class="pick-name">{{ c.name }}</span>
        <span class="pick-count">{{ c.docCount }} 个文档</span>
      </a-radio>
      <a-radio value="remove" class="pick-item">
        <ExportOutlined class="pick-icon pick-icon-remove" />
        <span class="pick-name">移出集合</span>
        <span class="pick-count">变为独立文件</span>
      </a-radio>
    </a-radio-group>
  </a-modal>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.pick-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  transition: background 0.15s, border-color 0.15s;
}
.pick-item:hover {
  background: var(--color-hover-bg);
}
/* 选中卡片：主色描边 + 品牌染底（a-radio 根元素即 wrapper，选中态类在同元素上） */
.pick-item.ant-radio-wrapper-checked,
.pick-item:has(input:checked) {
  border-color: var(--color-primary);
  background: var(--color-hover-tint);
}
.pick-icon {
  color: var(--color-primary);
  font-size: 14px;
}
.pick-icon-remove {
  color: var(--color-ink-tertiary);
}
.pick-name {
  font-size: 13px;
  color: var(--color-ink);
}
.pick-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-ink-tertiary);
}
</style>
