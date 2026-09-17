import { http } from './client'

/** Prompt 资产 key 视图（/prompt/keys） */
export interface PromptKeyView {
  promptKey: string
  currentVersion: number | null
  /** DB 当前版本与 classpath 代码内容是否有差异（开发改码未收编） */
  driftedFromCode: boolean
  /** 代码有此 key 但 DB 未建档（导入后新增文件，重启或收编即建档） */
  codeOnly?: boolean
  /** DB 有此 key 但代码无文件（线上创建）——版本历史即唯一真相 */
  codeMissing?: boolean
  /** 组织归类：链路（如 rag/agent/ingestion/vlm/other）与环节（后端集中声明） */
  chain?: string
  chainLabel?: string
  segment?: string
  segmentLabel?: string
  updateTime: string | null
}

/** Prompt 版本（sa_prompt_version 投影） */
export interface PromptVersion {
  id: number
  promptId: number
  versionNo: number
  content: string
  changeNote: string | null
  createTime: string
}

export function listPromptKeys() {
  return http.get<PromptKeyView[]>('/prompt/keys')
}

export function listPromptVersions(key: string) {
  return http.get<PromptVersion[]>('/prompt/versions', { params: { key } })
}

export function getPromptVersion(key: string, no?: number) {
  return http.get<PromptVersion>('/prompt/version', { params: { key, no } })
}

export function diffPrompt(key: string, from: number, to: number) {
  return http.get<{ from: PromptVersion; to: PromptVersion }>('/prompt/diff', { params: { key, from, to } })
}

export function createPromptVersion(key: string, content: string, changeNote: string) {
  return http.post<{ versionNo: number }>('/prompt/versions', { content, changeNote }, { params: { key } })
}

export function rollbackPrompt(key: string, to: number) {
  return http.post<{ versionNo: number }>('/prompt/rollback', null, { params: { key, to } })
}

export function syncPromptFromCode(key: string) {
  return http.post<{ versionNo: number }>('/prompt/sync-from-code', null, { params: { key } })
}
