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
  expectedAnswer?: string | null
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
  /** 标准答案（黄金集冗余投影，答案评测展示用） */
  expectedAnswer?: string | null
  /** 系统生成答案（answerEval 时 LLM 生成，与标准答案对照展示） */
  generatedAnswer?: string | null
  metricName: string
  score: number
  detail: string | null
  latencyMs: number | null
  attempt?: number | null
  remark?: string | null
  rewrite?: boolean | null
  /** 该条是否仅检索期望文档（per-question 上限对照；重评可覆盖原 run 设置） */
  perQuestion?: boolean | null
  paradigm?: string | null
  category?: string | null
  traceId?: string | null
}

/** 通用分页返回（后端 PageResult 镜像；total 按 item 计） */
export interface MetricsPage {
  total: number
  records: EvalMetric[]
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
  items: { question: string; expectedDocIds: string[]; expectedAnswer?: string; category?: string }[],
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
  /** 语料归入的文档集合 ID（未指定时自动按数据集名建） */
  collectionId: number
  /** 语料归入的文档集合名 */
  collectionName: string
  elapsedMs: number
}

/** 导入 LiveRAG 基准：同步执行（抽样 50 题约 2-5 分钟），返回统计 */
export async function importLiveRag(opts: {
  sampleSize?: number
  forceRefresh?: boolean
  datasetName?: string
  /** 语料归入的文档集合 ID；空则自动按数据集名查/建同名集合 */
  collectionId?: number
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
    /** 是否启用答案质量评测（生成答案 + LLM-as-judge 打分） */
    answerEval?: boolean
    /** per-question 检索模式：检索限定在该题 expected_doc_ids 内（实验开关） */
    perQuestion?: boolean
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

/**
 * 指标明细（item 级分页）：page/size 针对 item（每 item 含其全部指标行），
 * total 为 item 总数；后端不再返回 agent_trace 大字段。
 */
export async function getMetrics(runId: number, page = 1, size = 20): Promise<MetricsPage> {
  const { data } = await http.get<MetricsPage>(`/eval/runs/${runId}/metrics`, {
    params: { page, size },
  })
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
  opts?: { rewriteEnabled?: boolean; remark?: string; paradigm?: string; perQuestion?: boolean },
): Promise<{ runId: number; itemId: number; attempt: number }> {
  const { data } = await http.post(`/eval/runs/${runId}/items/${itemId}/reevaluate`, opts)
  return data
}

export interface ObserveLinks {
  traceUrl: string
  logUrl: string
  /** trace 详情深链模板（含 {traceId} 占位符）：消息气泡「OO 链路」按此跳转 */
  traceDetailUrlTemplate: string
  org: string
  email: string
  password: string
}

/** 链路追踪：从后端获取 OpenObserve 跳转链接 + 只读账号（地址由后端 openobserve.web-url 配置决定） */
export async function getObserveLinks(): Promise<ObserveLinks> {
  const { data } = await http.get<ObserveLinks>('/observe/links')
  return data
}

// ── OO trace 详情深链构建（对话气泡「链路」/ 轨迹列表 traceId 共用） ──
// 模板模块级缓存一次（import 即预取）；OO 要求 from/to 微秒时间戳，缺省会重定向到列表页
let ooTraceTpl = ''
getObserveLinks().then((l) => { ooTraceTpl = l.traceDetailUrlTemplate || '' }).catch(() => {})

/** 生成 trace 详情深链：tsMs 为消息时间（ms），窗口 ±10min；模板未就绪返回空串。 */
export function traceDetailUrl(traceId: string, tsMs?: number): string {
  if (!ooTraceTpl) return ''
  const base = tsMs ?? Date.now()
  return ooTraceTpl
    .replace('{traceId}', traceId)
    .replace('{from}', String((base - 10 * 60_000) * 1000))
    .replace('{to}', String((base + 10 * 60_000) * 1000))
}
