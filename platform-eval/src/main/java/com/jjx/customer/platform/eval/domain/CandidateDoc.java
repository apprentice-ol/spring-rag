package com.jjx.customer.platform.eval.domain;

/**
 * 转正辅助的候选文档（在目标集合内用该 bad case 的问题重新检索得到，人工勾选为 expected_doc_ids）。
 *
 * <p>badcase 本身"没召回对"，所以候选必须重新检索获取，不能从证据快照的召回明细里取。</p>
 *
 * @param docId   文档 id（与 chunk.metadata.doc_id、expected_doc_ids 同源口径）
 * @param docName 文档名（人类可读）
 * @param score   相关度分（融合/精排后）
 * @param channel 来源通道（VECTOR / KEYWORD / …）
 * @param preview 片段预览（截断，供人工判断）
 */
public record CandidateDoc(String docId, String docName, Double score, String channel, String preview) {
}
