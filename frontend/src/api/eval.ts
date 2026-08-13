import { http } from './client'

export interface EvalDataset {
  id: number
  name: string
  description: string
  itemCount: number
}

export interface EvalItem {
  id: number
  datasetId: number
  question: string
  expectedDocIds: string | null
  category: string | null
  source: string
  enabled: number
}

export interface EvalRun {
  id: number
  datasetId: number
  status: string // RUNNING / DONE / FAILED
  paradigm?: string | null
  total: number | null
  done: number
  paramSnapshot: string | null
  aggregateMetrics: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface EvalMetric {
  id: number
  runId: number
  itemId: number
  question: string | null
  retrievedDocIds: string | null
  retrievedDocNames: string | null
  expectedDocIds: string | null
  expectedDocNames: string | null
  metricName: string
  score: number
  detail: string | null
  latencyMs: number | null
  attempt?: number | null
  remark?: string | null
  rewrite?: boolean | null
  paradigm?: string | null
  category?: string | null
  traceId?: string | null
  agentTrace?: string | null
}

export async function listDatasets(): Promise<EvalDataset[]> {
  const { data } = await http.get<EvalDataset[]>('/eval/datasets')
  return data
}

export async function createDataset(name: string, description: string): Promise<{ id: number }> {
  const { data } = await http.post<{ id: number }>('/eval/datasets', { name, description })
  return data
}

export async function listItems(datasetId: number): Promise<EvalItem[]> {
  const { data } = await http.get<EvalItem[]>(`/eval/datasets/${datasetId}/items`)
  return data
}

export async function addItems(
  datasetId: number,
  items: { question: string; expectedDocIds: string[]; category?: string }[],
): Promise<{ added: number }> {
  const { data } = await http.post<{ added: number }>(`/eval/datasets/${datasetId}/items`, items)
  return data
}

export interface LiveRagImportResult {
  datasetId: number
  sampleSize: number
  imported: number
  skipped: number
  docsIngested: number
  docsFailed: number
  itemsSkipped: number
  elapsedMs: number
}

/** 导入 LiveRAG 基准：同步执行（抽样 50 题约 2-5 分钟），返回统计 */
export async function importLiveRag(opts: {
  sampleSize?: number
  forceRefresh?: boolean
  datasetName?: string
}): Promise<LiveRagImportResult> {
  // 同步导入 2-5 分钟，覆盖全局 60s 超时（10 分钟）
  const { data } = await http.post<LiveRagImportResult>('/eval/datasets/import/liverag', opts, {
    timeout: 600000,
  })
  return data
}

export async function triggerRun(
  datasetId: number,
  opts?: {
    category?: string
    limit?: number
    rewriteEnabled?: boolean
    paradigm?: string
    /** 指定只跑这些条目 id（精确子集）；非空时覆盖 category/limit，用于多范式同题对照 */
    itemIds?: number[]
  },
): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>('/eval/runs', { datasetId, ...opts })
  return data
}

export async function listRuns(opts?: { datasetId?: number; status?: string }): Promise<EvalRun[]> {
  const { data } = await http.get<EvalRun[]>('/eval/runs', { params: opts })
  return data
}

export async function getRun(runId: number): Promise<EvalRun> {
  const { data } = await http.get<EvalRun>(`/eval/runs/${runId}`)
  return data
}

export async function getMetrics(runId: number): Promise<EvalMetric[]> {
  const { data } = await http.get<EvalMetric[]>(`/eval/runs/${runId}/metrics`)
  return data
}

export async function updateDataset(id: number, name: string, description: string): Promise<unknown> {
  const { data } = await http.put(`/eval/datasets/${id}`, { name, description })
  return data
}

export async function deleteDataset(id: number): Promise<unknown> {
  const { data } = await http.delete(`/eval/datasets/${id}`)
  return data
}

export async function deleteItem(datasetId: number, itemId: number): Promise<unknown> {
  const { data } = await http.delete(`/eval/datasets/${datasetId}/items/${itemId}`)
  return data
}

export async function toggleItemEnabled(datasetId: number, itemId: number, enabled: number): Promise<unknown> {
  const { data } = await http.put(`/eval/datasets/${datasetId}/items/${itemId}/enabled`, { enabled })
  return data
}

export async function deleteRun(id: number): Promise<unknown> {
  const { data } = await http.delete(`/eval/runs/${id}`)
  return data
}

export async function retryRun(runId: number): Promise<{ runId: number }> {
  const { data } = await http.post<{ runId: number }>(`/eval/runs/${runId}/retry`)
  return data
}

/** 单条重评（保留历史，新增 attempt）：可选启用改写、可填备注 */
export async function reevaluateItem(
  runId: number,
  itemId: number,
  opts?: { rewriteEnabled?: boolean; remark?: string; paradigm?: string },
): Promise<{ runId: number; itemId: number; attempt: number }> {
  const { data } = await http.post(`/eval/runs/${runId}/items/${itemId}/reevaluate`, opts)
  return data
}

export interface ObserveLinks {
  traceUrl: string
  logUrl: string
  org: string
  email: string
  password: string
}

/** 链路追踪：从后端获取 OpenObserve 跳转链接 + 只读账号（地址由后端 openobserve.web-url 配置决定） */
export async function getObserveLinks(): Promise<ObserveLinks> {
  const { data } = await http.get<ObserveLinks>('/observe/links')
  return data
}
