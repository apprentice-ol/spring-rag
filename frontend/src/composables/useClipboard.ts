import { message } from 'ant-design-vue'

/**
 * 复制文本到剪贴板。
 * 优先 Clipboard API（仅安全上下文可用）；降级 textarea + execCommand（覆盖 http / host.docker.internal 等非安全上下文）。
 * @returns 是否成功
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 降级走 textarea */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/** 复制 + toast 反馈（label 用于提示文案，默认 traceId）。 */
export function copyWithToast(text: string, label = 'traceId'): void {
  copyText(text).then((ok) => {
    if (ok) message.success(`${label} 已复制`)
    else message.info('复制失败，请手动选中复制')
  })
}
