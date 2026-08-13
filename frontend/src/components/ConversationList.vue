<script setup lang="ts">
import { PlusOutlined, DeleteOutlined, MessageOutlined, MenuFoldOutlined } from '@ant-design/icons-vue'
import { conversations, activeId, selectConversation, newConversation, removeConversation } from '../composables/useChatState'

/** 选中会话后是否需要外层切换到聊天视图（移动端 tab 布局用） */
const emit = defineEmits<{ chat: []; collapse: [] }>()

/** 是否显示"收起会话栏"按钮（仅桌面端 ChatPanel 传入；移动端对话 Tab 不显示） */
defineProps<{ showCollapse?: boolean }>()

async function onSelect(convId: string) {
  await selectConversation(convId)
  emit('chat')
}

function onNew() {
  newConversation()
  emit('chat')
}

async function onRemove(convId: string) {
  await removeConversation(convId)
  emit('chat')
}
</script>

<template>
  <div class="conv-panel">
    <div class="conv-head">
      <span class="conv-head-title">对话</span>
      <div class="conv-head-actions">
        <a-button
          v-if="showCollapse"
          type="text"
          size="small"
          class="conv-collapse-btn"
          title="收起会话栏"
          @click="emit('collapse')"
        >
          <template #icon><MenuFoldOutlined /></template>
        </a-button>
        <a-button type="text" size="small" class="conv-new-btn" title="新建对话" @click="onNew">
          <template #icon><PlusOutlined /></template>
        </a-button>
      </div>
    </div>
    <div class="conv-list">
      <div
        v-for="c in conversations"
        :key="c.conversationId"
        class="conv-item"
        :class="{ active: c.conversationId === activeId }"
        @click="onSelect(c.conversationId)"
      >
        <MessageOutlined class="conv-item-icon" />
        <span class="conv-item-title">{{ c.title }}</span>
        <a-button type="text" size="small" class="conv-item-del" @click.stop="onRemove(c.conversationId)">
          <template #icon><DeleteOutlined /></template>
        </a-button>
      </div>
      <div v-if="!conversations.length" class="conv-empty">暂无对话</div>
    </div>
  </div>
</template>

<style scoped>
.conv-panel {
  height: 100%;
  display: flex; flex-direction: column;
  background: var(--color-surface);
}
.conv-head {
  display: flex; align-items: center; justify-content: space-between; gap: 4px;
  padding: 12px 14px 8px; border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
}
.conv-head-title {
  font-size: 12px; font-weight: 600; letter-spacing: 0.04em;
  color: var(--color-ink-tertiary); text-transform: uppercase;
}
.conv-new-btn { color: var(--color-ink-secondary); }
.conv-head-actions { display: flex; align-items: center; gap: 0; flex-shrink: 0; }
.conv-collapse-btn { color: var(--color-ink-tertiary); }
.conv-collapse-btn:hover { color: var(--color-primary); }
.conv-list { flex: 1; overflow-y: auto; padding: 4px 0; }
.conv-item {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 14px; cursor: pointer; transition: background .12s;
  font-size: 13px; color: var(--color-ink);
}
.conv-item:hover { background: var(--color-surface-secondary); }
.conv-item.active { background: rgba(15,118,110,.08); color: var(--color-primary); }
.conv-item-icon { font-size: 12px; flex-shrink: 0; color: var(--color-ink-tertiary); }
.conv-item-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-item-del { opacity: 0; color: var(--color-ink-tertiary); flex-shrink: 0; }
.conv-item:hover .conv-item-del { opacity: 1; }
.conv-item-del:hover { color: #ef4444; }
.conv-empty { padding: 20px; text-align: center; font-size: 12px; color: var(--color-ink-tertiary); }

@media (max-width: 768px) {
  .conv-item-del { opacity: 1; } /* 移动端无 hover */
}
</style>
