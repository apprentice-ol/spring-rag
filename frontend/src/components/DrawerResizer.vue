<script setup lang="ts">
/**
 * 右侧抽屉的拖宽手柄（配 useDrawerWidth 使用）。
 *
 * 定位用 fixed + right 跟随宽度，而不是塞进抽屉插槽里——塞进去就得和 antd 的
 * body padding / header 层叠较劲，还要提防 teleport 与 overflow 裁剪。
 *
 * 无手柄视觉：只保留 8px 抓取带 + col-resize 光标提示，不画竖线
 * （用户偏好：抽屉边缘保持干净，扫到光标变化即知可拖）。
 */
defineProps<{
  /** 当前抽屉宽度（px），手柄贴着它左缘 */
  width: number
}>()

const emit = defineEmits<{
  start: [event: PointerEvent]
  reset: []
  nudge: [step: number]
}>()
</script>

<template>
  <div
    class="drawer-resizer"
    :style="{ right: `${width}px` }"
    role="separator"
    tabindex="0"
    aria-orientation="vertical"
    aria-label="调整抽屉宽度，双击恢复默认"
    @pointerdown="emit('start', $event)"
    @dblclick="emit('reset')"
    @keydown.left.prevent="emit('nudge', -40)"
    @keydown.right.prevent="emit('nudge', 40)"
  />
</template>

<style scoped>
.drawer-resizer {
  position: fixed; top: 0; bottom: 0; width: 8px;
  z-index: 1001; /* antd 抽屉内容层的 z-index 是 1000，手柄要浮在它上面才抓得到 */
  cursor: col-resize; outline: none;
}
</style>
