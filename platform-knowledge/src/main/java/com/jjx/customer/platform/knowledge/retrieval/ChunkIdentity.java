package com.jjx.customer.platform.knowledge.retrieval;

import cn.hutool.crypto.digest.DigestUtil;
import java.util.Map;

/**
 * 分片去重标识：{@code doc_id} + 内容全文 SHA-256。
 *
 * <p><b>为什么必须带 doc_id</b>：同一段文本在语料里可能挂在多个 doc_id 下——LiveRAG 评测集
 * 把每道题的支撑文档拆成 {@code LiveRAG-{题号}-doc{n}.md}，同一篇文章被多道题引用，
 * 于是同一份内容会有多个逐字节相同的副本（实测 {@code LiveRAG-235-doc1.md} 与
 * {@code LiveRAG-741-doc2.md} 11 个分片 md5 全等）。只按内容哈希去重会把期望文档和它的
 * 副本合并成一条，留哪份取决于通道顺序——而评测按 doc_id 打分，合并掉期望那份就是 0 分，
 * 且表现为"同一道题时好时坏"。</p>
 *
 * <p>带 doc_id 后：跨文档的孪生副本各自保留（期望 doc_id 不再丢失）；同一文档内的重复内容
 * 仍然合并（不把同样的文本重复喂给模型）。本口径与 {@code BaiLianRerankClient.dedupById}
 * 已有的 {@code doc_id + ":" + content} 一致——两处去重键不一致本身就是缺陷。</p>
 */
public final class ChunkIdentity {

    private ChunkIdentity() {
    }

    /**
     * 分片去重键。
     *
     * @param chunk 命中分片（null 安全，退化为空内容哈希）
     * @return {@code doc_id#内容SHA-256}；doc_id 缺失时退化为纯内容哈希
     */
    public static String of(RetrievedChunk chunk) {
        if (chunk == null) {
            return of(null, null);
        }
        return of(chunk.getMetadata(), chunk.getContent());
    }

    /**
     * 分片去重键（拆参版本，供只有元数据与正文的调用方使用）。
     *
     * @param metadata 分片元数据（取 {@code doc_id}；null/缺失时退化为纯内容哈希）
     * @param content  分片正文
     * @return {@code doc_id#内容SHA-256}；doc_id 缺失时退化为纯内容哈希
     */
    public static String of(Map<String, Object> metadata, String content) {
        String contentHash = DigestUtil.sha256Hex(content == null ? "" : content);
        Object docId = metadata == null ? null : metadata.get("doc_id");
        String docIdText = docId == null ? "" : String.valueOf(docId);
        return docIdText.isBlank() ? contentHash : docIdText + "#" + contentHash;
    }
}
