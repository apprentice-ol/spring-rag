<script setup lang="ts">
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
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
        📁 {{ c.name }}（{{ c.docCount }} 个）
      </a-radio>
      <a-radio value="remove" class="pick-item">🚫 移出集合（变为独立文件）</a-radio>
    </a-radio-group>
  </a-modal>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.pick-item {
  display: flex;
  align-items: center;
  padding: 6px 0;
}
</style>
