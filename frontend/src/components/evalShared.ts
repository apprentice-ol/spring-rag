/** 评测各 Tab / 详情抽屉共享的指标知识与纯工具函数。 */

export interface MetricKnow {
  label: string
  stage: string // 衡量环节
  dir: string // 高好低好
  desc: string
  judge: (mean: number) => string
}

/** 指标知识库：基础指标名（去掉 _at_k 后缀）→ 含义 / 环节 / 方向 / 解读 */
export const METRIC_KNOWLEDGE: Record<string, MetricKnow> = {
  recall_at: {
    label: '召回率 Recall@k',
    stage: '检索环节',
    dir: '越高越好',
    desc: '期望文档在前 k 条检索结果中被命中的比例（文档级去重后计算）。能否把相关文档「捞回来」是 RAG 的下限指标 —— 漏检的文档答案必错，该项直接归零。',
    judge: (m) =>
      m >= 0.95
        ? '当前值优秀：支持文档几乎全部被召回，检索无遗漏。'
        : m >= 0.8
          ? '当前值良好：少量题漏检，可调大 recallBudget / 降低相似度阈值。'
          : '当前值偏低：存在系统性漏检，优先查分块粒度与多通道召回参数。',
  },
  precision_at: {
    label: '精确率 Precision@k',
    stage: '排序环节',
    dir: '越高越好',
    desc: '前 k 条结果中期望文档的占比（分母固定为 k）。衡量结果的「纯度」—— 混入的无关文档越多越低。',
    judge: (m) =>
      m >= 0.6
        ? '当前值优秀：前 k 条几乎全是期望文档。'
        : m >= 0.3
          ? '当前值一般：前 k 条混入了不少语义相近的干扰文档。'
          : '当前值偏低：检索结果宽泛、排序不够精准，是 Rerank / 阈值 / RRF 权重调优的主要对象。',
  },
  mrr: {
    label: '平均倒数排名 MRR',
    stage: '排序环节',
    dir: '越高越好',
    desc: '第一个正确文档出现位置的倒数，再取平均。1.0 表示全部题目首位即命中正确文档。',
    judge: (m) =>
      m >= 0.95
        ? '当前值优秀：正确文档几乎都在第 1 位。'
        : m >= 0.7
          ? '当前值良好：多数首位命中，个别题正确文档靠后，可调 Rerank 力度。'
          : '当前值偏低：正确文档常排在后面，优先调精排环节。',
  },
  ndcg_at: {
    label: '归一化折损累计增益 nDCG@k',
    stage: '排序环节',
    dir: '越高越好',
    desc: '按位置加权（排名越靠前权重越大）的相关性总和，与理想排序的比值。整体排序质量的综合评价。',
    judge: (m) =>
      m >= 0.95
        ? '当前值优秀：排序质量接近理想。'
        : m >= 0.7
          ? '当前值良好：排序基本合理，个别位置需要优化。'
          : '当前值偏低：排序整体欠佳，与 Precision@k 一起看定位问题。',
  },
}

/** 基础指标名（去掉 _at_k 数字后缀），如 recall_at_5 → recall_at */
export function metricKey(name: string): string {
  return name.replace(/_\d+$/, '')
}

/** 指标基础名 → 中文（灵活：新增指标只需在此 map 加条目，metricLabel 自动适配；未配置则原样返回） */
const METRIC_CN: Record<string, string> = {
  recall_at: '召回率',
  precision_at: '精确率',
  mrr: 'MRR',
  ndcg_at: 'nDCG',
}

/** 指标名 → 中文标签（带 k），如 recall_at_5 → 召回率@5；mrr → MRR；未知指标原样返回 */
export function metricLabel(name: string): string {
  const base = metricKey(name)
  const cn = METRIC_CN[base]
  if (!cn) return name
  if (base === 'mrr') return cn
  const m = name.match(/_(\d+)$/)
  return m ? `${cn}@${m[1]}` : cn
}

/** 是否检索环节指标（蓝），否则排序环节（橙）—— 用于卡片配色 */
export function isRetrievalMetric(name: string): boolean {
  return metricKey(name) === 'recall_at'
}

/** 按均值给颜色档：≥0.8 绿 / 0.5–0.8 黄 / <0.5 红 */
export function scoreTone(mean: number): 'good' | 'mid' | 'bad' {
  if (mean >= 0.8) return 'good'
  if (mean >= 0.5) return 'mid'
  return 'bad'
}

export function fmtScore(v: unknown): string {
  if (v == null) return '-'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(4) : String(v)
}

export function parseDocIds(json: string | null): string[] {
  if (!json) return []
  try {
    return JSON.parse(json)
  } catch {
    return []
  }
}

export function parseDetail(
  json: string | null,
): { hitCount?: number; expectedCount?: number; retrievedCount?: number; expectedDocIds?: string[]; expectedDocNames?: string[] } | null {
  if (!json) return null
  try {
    return JSON.parse(json)
  } catch {
    return null
  }
}

export function statusText(s: string): string {
  return ({ RUNNING: '运行中', DONE: '完成', FAILED: '失败', PENDING: '等待' } as Record<string, string>)[s] || s
}

export const SOURCE_LABEL: Record<string, string> = {
  liverag: 'LiveRAG 自动导入',
  builtin: '人工构造',
  feedback: 'badcase 回流',
}

/** 从 run.aggregateMetrics（jsonb 字符串）解析出聚合对象 */
export function parseAggregate(run: { aggregateMetrics?: string | null } | null): Record<
  string,
  { count: number; mean: number; median: number; min: number; max: number }
> | null {
  if (!run?.aggregateMetrics) return null
  try {
    return JSON.parse(run.aggregateMetrics)
  } catch {
    return null
  }
}

export interface RunParams {
  topK?: number
  threshold?: number
  recallBudget?: number
  candidateLimit?: number
  contextTopK?: number
  rewrite?: boolean
}

/** 从 run.paramSnapshot（jsonb 字符串）解析出本次检索参数 */
export function parseParamSnapshot(run: { paramSnapshot?: string | null } | null): RunParams | null {
  if (!run?.paramSnapshot) return null
  try {
    return JSON.parse(run.paramSnapshot)
  } catch {
    return null
  }
}

/** LiveRAG 问题类型（answer-type-categorization）说明：数据集条目「分类」列的取值含义 */
export const CATEGORY_DESC: { key: string; desc: string }[] = [
  { key: 'factoid', desc: '事实型 — 询问具体事实（人/地/时间/数量等确定信息）' },
  { key: 'list', desc: '列表型 — 需列举多个答案项' },
  { key: 'yes/no', desc: '是非型 — 是或否的判断' },
  { key: 'definition', desc: '定义型 — 概念的定义/含义' },
  { key: 'explanation', desc: '解释型 — 原理/原因/机制的解释' },
  { key: 'comparison', desc: '对比型 — 两者或多者比较异同' },
  { key: 'multi-aspect', desc: '多面型 — 涉及多个方面的综合问题' },
]

/** 分类 key → 中文短名（表格/筛选展示用） */
export const CATEGORY_LABEL: Record<string, string> = {
  factoid: '事实型',
  list: '列表型',
  'yes/no': '是非型',
  definition: '定义型',
  explanation: '解释型',
  comparison: '对比型',
  'multi-aspect': '多面型',
}

/** 分类 key → 中文短名；未知值原样返回，空值返回 '-' */
export function categoryLabel(v: string | null | undefined): string {
  if (!v) return '-'
  return CATEGORY_LABEL[v] ?? v
}

/** agent 范式选项（与后端 RagAgent 范式对齐：naive/react） */
export const PARADIGMS: { value: string; label: string; desc: string }[] = [
  { value: 'naive', label: 'Naive', desc: '单次检索' },
  { value: 'react', label: 'ReAct', desc: '自主循环' },
]

/** 范式 value → 中文标签；未知值原样返回，空值返回 '-' */
export function paradigmLabel(v: string | null | undefined): string {
  if (!v) return '-'
  return PARADIGMS.find((p) => p.value === v)?.label ?? v
}
