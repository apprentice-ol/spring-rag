import {http} from './client'

/**
 * bad case 归集 API（统一模型：badcase 即 sa_eval_item 的一种）。
 * 设计见 docs/plans/2026-10-11-badcase-collection.md。
 */

/** 证据快照里的命中片段摘要（后端 BadcaseEvidence.ChunkBrief 镜像） */
export interface BadcaseEvidenceChunk {
    ref?: number
    docId?: string
    docName?: string
    score?: number
    originalScore?: number
    channel?: string
    preview?: string
}

/** 证据快照（后端 BadcaseEvidence 镜像；归集时从原始现场固化） */
export interface BadcaseEvidence {
    question?: string
    conversationId?: string
    answer?: string
    traceId?: string
    paradigm?: string
    hasTrace?: boolean
    intent?: {
        domain?: string
        confidence?: number
        needsRetrieval?: boolean
        needsWebSearch?: boolean
        reason?: string
        routeTarget?: string
    }
    retrieval?: {
        channelCandidates?: number
        finalHits?: number
        channels?: { name?: string; hits?: number }[]
        chunks?: BadcaseEvidenceChunk[]
    }
    citations?: { docId?: string; docName?: string; chunkCount?: number }[]
    /** 命中片段所属文档集合（按命中条数排序；domain 推荐的运行期信号） */
    hitCollections?: { collectionId?: number; name?: string; hits?: number }[]
    note?: string
}

/** 归因结论（规则预判 + 人工确认） */
export interface BadcaseTriage {
    stage?: string
    auto?: boolean
    note?: string
}

export interface BadcaseView {
    id: number
    datasetId: number
    question: string
    origin?: string | null
    domain?: string | null
    badcaseStatus?: string | null
    feedbackReason?: string | null
    feedbackNote?: string | null
    evidence?: BadcaseEvidence | null
    triage?: BadcaseTriage | null
    expectedAnswer?: string | null
    expectedDocCount?: number | null
    createTime?: string | null
    updateTime?: string | null
}

export interface BadcaseStats {
    total: number
    byStatus: Record<string, number>
    byStage: Record<string, number>
    byDomain: Record<string, number>
    byOrigin: Record<string, number>
}

/** 转正辅助的候选文档 */
export interface CandidateDoc {
    docId?: string
    docName?: string
    score?: number
    channel?: string
    preview?: string
}

export interface BadcasePage {
    total: number
    records: BadcaseView[]
}

/** 归集（点踩入口；幂等 by messageId：重复提交=更新原因/备注） */
export async function collectBadcase(payload: {
    messageId: number
    reason?: string
    note?: string
    domain?: string
    origin?: string
}): Promise<BadcaseView> {
    const {data} = await http.post<BadcaseView>('/eval/badcases', payload)
    return data
}

/** 手工录入（管理页；无线上现场，证据为空、归因 UNKNOWN） */
export async function createManualBadcase(payload: {
    question: string
    note?: string
    domain?: string
}): Promise<BadcaseView> {
    const {data} = await http.post<BadcaseView>('/eval/badcases/manual', payload)
    return data
}

/** 列表（管理页）：来源/领域/状态/关键词筛选 + 分页 */
export async function listBadcases(params: {
    origin?: string
    domain?: string
    status?: string
    keyword?: string
    page?: number
    size?: number
}): Promise<BadcasePage> {
    const {data} = await http.get<BadcasePage>('/eval/badcases', {params})
    return data
}

export async function getBadcase(id: number): Promise<BadcaseView> {
    const {data} = await http.get<BadcaseView>(`/eval/badcases/${id}`)
    return data
}

/** 归因与领域确认（人工）：domain 非空则一并更新 */
export async function updateTriage(id: number, stage: string, note?: string, domain?: string): Promise<BadcaseView> {
    const {data} = await http.put<BadcaseView>(`/eval/badcases/${id}/triage`, {stage, note, domain})
    return data
}

/** 忽略 / 恢复（IGNORED ↔ DRAFT） */
export async function updateBadcaseStatus(id: number, status: string): Promise<BadcaseView> {
    const {data} = await http.put<BadcaseView>(`/eval/badcases/${id}/status`, {status})
    return data
}

/** 删除（撤销误点反馈等）；已转正（READY）的后端拒绝 */
export async function deleteBadcase(id: number): Promise<void> {
    await http.delete(`/eval/badcases/${id}`)
}

/** 分布统计（领域 × 环节） */
export async function badcaseStats(): Promise<BadcaseStats> {
    const {data} = await http.get<BadcaseStats>('/eval/badcases/stats')
    return data
}

/** 领域字典（配置下发） */
export async function badcaseDomains(): Promise<string[]> {
    const {data} = await http.get<string[]>('/eval/badcases/domains')
    return data
}

/** 批量查已反馈标记（历史消息回填：messageId → badcase 条目 id） */
export async function feedbackFlags(messageIds: number[]): Promise<Record<string, number>> {
    const {data} = await http.get<Record<string, number>>('/eval/badcases/feedback-flags', {
        params: {messageIds: messageIds.join(',')},
    })
    return data
}

/** 转正辅助：在目标集合范围内重新检索候选文档（勾选为 expected_doc_ids） */
export async function searchCandidates(id: number, targetDatasetId?: number | null, topN?: number): Promise<CandidateDoc[]> {
    const {data} = await http.post<CandidateDoc[]>(`/eval/badcases/${id}/search-candidates`, {targetDatasetId, topN})
    return data
}

/** 转正辅助：LLM 基于勾选文档起草标准答案（人工修订后提交） */
export async function draftAnswer(id: number, docIds: string[]): Promise<string> {
    const {data} = await http.post<{ answer: string }>(`/eval/badcases/${id}/draft-answer`, {docIds})
    return data.answer
}

/** 转正：补 ground truth → 搬到目标领域数据集（READY + enabled=1） */
export async function promoteBadcase(id: number, payload: {
    targetDatasetId: number
    expectedDocIds: string[]
    expectedAnswer?: string
    domain?: string
}): Promise<BadcaseView> {
    const {data} = await http.post<BadcaseView>(`/eval/badcases/${id}/promote`, payload)
    return data
}

/** 点踩原因选项（与后端 feedback_reason 取值对齐） */
export const FEEDBACK_REASONS = [
    {value: 'answer_irrelevant', label: '答非所问'},
    {value: 'content_wrong', label: '内容错误'},
    {value: 'incomplete', label: '信息不全'},
    {value: 'not_found', label: '没找到资料'},
    {value: 'other', label: '其他'},
]

/** 归因环节标签（与后端 TriageStage 对齐） */
export const TRIAGE_STAGE_LABEL: Record<string, string> = {
    UNKNOWN: '证据不足',
    INTENT_MISROUTE: '意图误判',
    NO_RECALL: '未召回',
    FILTERED_OUT: '后处理砍光',
    RANKING: '排序靠后',
    GENERATION: '生成问题',
}

/** 归集渠道标签（与后端 BadcaseOrigin 对齐） */
export const ORIGIN_LABEL: Record<string, string> = {
    online_dislike: '聊天点踩',
    eval_low: '评测低分',
    trace_pick: '巡检挑选',
    manual: '手工录入',
}

/** badcase 状态标签（与后端 BadcaseStatus 对齐） */
export const BADCASE_STATUS_LABEL: Record<string, string> = {
    DRAFT: '待补标准答案',
    READY: '已转正',
    IGNORED: '已忽略',
}
