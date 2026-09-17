import { http } from './client'

/** Prompt 能力包（/prompt-bundle）：组包 → 发布 release（不可变快照）→ 绑定 agent 骨架。 */

/** 包视图（GET /prompt-bundle/list） */
export interface BundleView {
  id: number
  name: string
  description: string | null
  /** 适用骨架（null = 通用包，可作任何绑定的基座） */
  agentType: string | null
  /** 最新 release 号（null = 从未发布） */
  latestRelease: number | null
  updateTime: string | null
}

/**
 * release（不可变快照）。存储是 {key: versionId}，后端返回前已解析为
 * {promptKey: 版本号}——versionId 是全局自增主键，对展示没有意义。
 */
export interface BundleRelease {
  id: number
  bundleId: number
  releaseNo: number
  items: Record<string, number>
  changeNote: string | null
  createTime: string | null
}

/** 绑定：骨架 → (基座包, 特化包) 活引用 */
export interface PromptBinding {
  id: number
  agentType: string
  baseBundleId: number | null
  overlayBundleId: number | null
  updateTime: string | null
}

export function listBundles() {
  return http.get<BundleView[]>('/prompt-bundle/list')
}

export function createBundle(name: string, description: string, agentType?: string) {
  return http.post('/prompt-bundle/create', { name, description, agentType: agentType || null })
}

export function forkBundle(bundleId: number, name: string) {
  return http.post(`/prompt-bundle/${bundleId}/fork`, { name })
}

export function listReleases(bundleId: number) {
  return http.get<BundleRelease[]>(`/prompt-bundle/${bundleId}/releases`)
}

/** 删除包（连同其全部 release）；仍被 Agent 绑定时后端拒绝并列出骨架 */
export function deleteBundle(bundleId: number) {
  return http.delete(`/prompt-bundle/${bundleId}`)
}

/** 发布 release：items 为 {promptKey: 版本号}（后端固化为 versionId 快照） */
export function publishRelease(bundleId: number, items: Record<string, number>, changeNote: string) {
  return http.post<{ releaseNo: number }>(`/prompt-bundle/${bundleId}/releases`, { items, changeNote })
}

/** 绑定切换（后端即时校验 requiredKeys 覆盖，缺 key 拒绝并报缺哪些） */
export function bindAgentType(agentType: string, baseBundleId: number | null, overlayBundleId: number | null) {
  return http.post('/prompt-bundle/bind', { agentType, baseBundleId, overlayBundleId })
}

/** 解绑（回基线 classpath） */
export function unbindAgentType(agentType: string) {
  return http.post('/prompt-bundle/unbind', { agentType })
}

export function listBindings() {
  return http.get<PromptBinding[]>('/prompt-bundle/bindings')
}
