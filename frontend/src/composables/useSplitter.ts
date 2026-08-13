import { ref, onUnmounted, type Ref } from 'vue'

interface SplitterConfig {
  /** 初始宽度（px） */
  initial: number
  /** 最小宽度（px） */
  min: number
  /** 最大宽度（px） */
  max: number
  /** 持久化 key（传则写入 localStorage） */
  storageKey?: string
}

export interface HorizontalSplitter {
  /** 当前宽度（px），绑定到元素的 :style="{ width: width + 'px' }" */
  width: Ref<number>
  /** 是否正在拖拽 */
  dragging: Ref<boolean>
  /** pointerdown 时调用（绑定在分割条上） */
  start: (e: PointerEvent) => void
}

/**
 * 水平拖拽分割器：把一条竖向分割条左右拖动改变左侧面板宽度。
 * 用 PointerEvent 统一鼠标 + 触屏；move/up 绑 window，鼠标移出分割条也能继续拖。
 *
 * 拖拽时给 body 加 `splitter-dragging` class（配合全局 CSS 禁止选中文本、固定 col-resize 光标）。
 */
export function useHorizontalSplitter(config: SplitterConfig): HorizontalSplitter {
  const width = ref(load())
  const dragging = ref(false)
  let startX = 0
  let startW = 0

  function load(): number {
    if (config.storageKey) {
      const saved = Number(localStorage.getItem(config.storageKey))
      if (!Number.isNaN(saved) && saved >= config.min && saved <= config.max) {
        return saved
      }
    }
    return config.initial
  }

  function onMove(e: PointerEvent) {
    if (!dragging.value) return
    const delta = e.clientX - startX
    width.value = Math.min(config.max, Math.max(config.min, startW + delta))
  }

  function onUp() {
    if (!dragging.value) return
    dragging.value = false
    document.body.classList.remove('splitter-dragging')
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    if (config.storageKey) {
      localStorage.setItem(config.storageKey, String(width.value))
    }
  }

  function start(e: PointerEvent) {
    dragging.value = true
    startX = e.clientX
    startW = width.value
    document.body.classList.add('splitter-dragging')
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    e.preventDefault()
  }

  onUnmounted(() => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    document.body.classList.remove('splitter-dragging')
  })

  return { width, dragging, start }
}

/** 响应式断点：是否为移动端（< 768px） */
export function useIsMobile(breakpoint = 768) {
  const isMobile = ref(
    typeof window !== 'undefined' ? window.innerWidth < breakpoint : false
  )
  function onResize() {
    isMobile.value = window.innerWidth < breakpoint
  }
  window.addEventListener('resize', onResize)
  onUnmounted(() => window.removeEventListener('resize', onResize))
  return isMobile
}
