<script setup lang="ts">
import { computed, ref } from 'vue'
import ConversationList from './ConversationList.vue'
import ChatView from './ChatView.vue'
import { useHorizontalSplitter } from '../composables/useSplitter'

/**
 * 桌面端对话面板：会话列表（可拖拽调宽 + 可收起）+ 聊天视图。
 * 移动端不渲染本组件（App.vue 用底部 Tab 分别展示 ConversationList / ChatView）。
 */
const convSplit = useHorizontalSplitter({
  initial: 220, min: 180, max: 340, storageKey: 'rag_conv_w',
})
const convWidthStyle = computed(() => `${convSplit.width.value}px`)

// 会话栏收起状态（持久化）
const convCollapsed = ref(localStorage.getItem('rag_conv_collapsed') === '1')

function toggleConv() {
  convCollapsed.value = !convCollapsed.value
  localStorage.setItem('rag_conv_collapsed', convCollapsed.value ? '1' : '0')
}
</script>

<template>
  <div class="chat-layout">
    <aside
      class="conv-shell"
      :class="{ collapsed: convCollapsed }"
      :style="{ width: convCollapsed ? '0px' : convWidthStyle }"
    >
      <ConversationList show-collapse @collapse="toggleConv" />
    </aside>

    <!-- 桌面端会话栏拖拽分割条（收起时不显示） -->
    <div
      v-if="!convCollapsed"
      class="splitter-bar"
      :class="{ dragging: convSplit.dragging.value }"
      @pointerdown="convSplit.start"
    />

    <ChatView :conv-collapsed="convCollapsed" @toggle-conv="toggleConv" />
  </div>
</template>

<style scoped>
/* flex:1 撑满 app-body（否则宽度靠内容 fit-content——答案窄/消息少时整个布局收缩变窄） */
.chat-layout { display:flex; height:100%; flex:1; min-width:0; }
.conv-shell {
  flex-shrink:0; min-width:0; overflow:hidden;
  border-right:1px solid var(--color-border);
  transition: width 0.2s ease, opacity 0.2s ease;
}
.conv-shell.collapsed { opacity:0; border-right:none; }
</style>
