import { ref, onMounted, onUnmounted } from 'vue'

/**
 * 右侧抽屉宽度拖拽（全站抽屉通用，轨迹抽屉 / badcase 详情抽屉共用）。
 *
 * 一屏装得下多少内容，取决于读者此刻在看什么：扫一眼 720 够，逐条比对就嫌挤。
 * 把宽度交给他自己拖，比替他猜一个固定值更靠谱。
 *
 * 设计决策：
 * - 上限跟着窗口走（min(maxWidth, 视口 92%)）而不是取常量——一条 1200px 的抽屉
 *   在 1280 宽的屏上会把正文整个盖住，而抽屉的用途恰恰是「对着正文看」；
 * - 拖动语义是「指针到窗口右缘的距离」而非增量累加，天然免疫抖动；
 * - 拖动中只改内存值，松手才落 localStorage——每帧写一次是白费；
 * - 存储里的值可能是在更大的屏上写下的，读回来与窗口 resize 时都再钳一次；
 * - Pointer 事件（触控可用），配 DrawerResizer 组件的键盘微调 / 双击复位。
 */
export interface DrawerWidthOptions {
  /** localStorage 持久化键（空串则不持久化，每次用默认宽） */
  storageKey: string
  defaultWidth: number
  minWidth: number
  maxWidth: number
}

export function useDrawerWidth(opts: DrawerWidthOptions) {
  const { storageKey, defaultWidth, minWidth, maxWidth } = opts

  function clamp(value: number): number {
    const viewport = typeof window === 'undefined' ? 800 : window.innerWidth
    const max = Math.max(minWidth, Math.min(maxWidth, Math.round(viewport * 0.92)))
    if (!Number.isFinite(value)) return Math.min(defaultWidth, max)
    return Math.min(max, Math.max(minWidth, Math.round(value)))
  }

  function readStored(): number {
    try {
      const raw = window.localStorage.getItem(storageKey)
      return raw === null ? defaultWidth : Number(raw)
    } catch {
      return defaultWidth // 隐私模式下 localStorage 不可用
    }
  }

  const width = ref(clamp(readStored()))

  function persist() {
    if (!storageKey) return
    try {
      window.localStorage.setItem(storageKey, String(width.value))
    } catch {
      /* 写入失败只影响下次打开的默认宽度，不值得打扰用户 */
    }
  }

  /** 拖动中只改内存值，松手才落盘 */
  function startResize(event: PointerEvent) {
    event.preventDefault()
    const onMove = (move: PointerEvent) => {
      // 抽屉贴右边，所以「指针到窗口右缘的距离」才是它该有的宽度
      width.value = clamp(window.innerWidth - move.clientX)
    }
    const onUp = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      persist()
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
  }

  function reset() {
    width.value = clamp(defaultWidth)
    persist()
  }

  /** 键盘微调：手柄可聚焦，方向键各挪一档 */
  function nudge(step: number) {
    width.value = clamp(width.value + step)
    persist()
  }

  /** 窗口变小后原宽度可能盖住整个正文，跟着收一收 */
  function onViewportResize() {
    const clamped = clamp(width.value)
    if (clamped !== width.value) width.value = clamped
  }
  onMounted(() => window.addEventListener('resize', onViewportResize))
  onUnmounted(() => window.removeEventListener('resize', onViewportResize))

  return { width, startResize, reset, nudge }
}
