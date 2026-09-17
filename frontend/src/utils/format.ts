/**
 * 全站共享的格式化与状态映射（评测侧指标工具在 components/evalShared.ts）。
 * 目标：消灭各页重复的 fmtTime / fmtLatency / STATUS_COLOR / 占位符写法。
 */

/** 空值统一占位符（全站唯一；不用 '-' 或 '（无）'） */
export const EMPTY = '—'

/** ISO 字符串 / 时间戳 → 'YYYY-MM-DD HH:mm:ss'；空值或无法解析返回占位符 */
export function fmtTime(v?: string | number | null): string {
  if (v == null || v === '') return EMPTY
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 毫秒 → '823 ms' / '1.24 s'；空值返回占位符 */
export function fmtLatency(ms?: number | null): string {
  if (ms == null) return EMPTY
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(2)} s`
}

/** 入库任务状态 → antd tag color（文档管理 / 集合文档表共用） */
export const INGEST_STATUS_COLOR: Record<string, string> = {
  DONE: 'green',
  PROCESSING: 'processing',
  PENDING: 'orange',
  FAILED: 'red',
}

/** 入库任务状态 → 中文 */
export const INGEST_STATUS_TEXT: Record<string, string> = {
  DONE: '完成',
  PROCESSING: '处理中',
  PENDING: '待处理',
  FAILED: '失败',
}

/** 评测运行 / Live 对照状态 → antd tag color（EvalRunTab / ComparePanel 共用） */
export const RUN_STATUS_COLOR: Record<string, string> = {
  DONE: 'green',
  RUNNING: 'processing',
  FAILED: 'red',
  PENDING: 'orange',
}
