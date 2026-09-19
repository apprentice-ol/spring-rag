package com.jjx.customer.platform.business.knowledge.rag;

import com.jjx.customer.platform.common.util.TextPreviews;
import com.jjx.customer.platform.document.DocumentCatalog;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 上下文组装器：检索块 → 分组拼装的上下文文本 + 引用溯源映射。
 *
 * <p>属于编排层装配能力（在线编排与评测跑批共用同一实现，保证「评测输入 = 线上推给 LLM 的数据」）。</p>
 *
 * <p>引用溯源项 {@link Citation} 的 ref 对应提示词里 {@code <content ref="N">} 的 N，
 * 前端据此渲染 [N] 角标与来源面板。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagContextAssembler {

    private final DocumentCatalog documentCatalog;

    /** 检索上下文组装结果：text = 拼好的上下文，docCount = 命中文档数，citations = 引用溯源映射 */
    public record RagContext(String text, int docCount, List<Citation> citations) {
    }

    /** 回答引用溯源项 */
    public record Citation(int ref, String docId, String docName, int chunkCount,
                           String preview, String sourceLocation) {
    }

    /**
     * 组装检索上下文（对齐 ragent DefaultContextFormatter 的按文档分组 + 阅读顺序还原）。
     * <p>一个 {@code <content ref="N">} = 一份文档的全部命中块按 {@code chunk_index} 拼接后的连续文本。
     * LLM 把每个 content 当作一份完整资料、跨片段连贯理解；chunk 级平铺会把同一文档相邻块拆成
     * 独立"资料"，提示词"不同 content 之间注意张冠李戴"会压制跨块整合，块间关联丢失。
     * 刻意不注入文档名与分数：标题/分数进入上下文会诱导"出自《XX》"或偏向高分块。
     */
    public RagContext buildContextText(List<RetrievedChunk> retrievedChunkList) {
        // 按 doc_id 分组（LinkedHashMap 保持首次出现顺序=相关性顺序），组内按 chunk_index 还原原文顺序；
        // doc_id 缺失的块各自单独成组（__nodoc__ + 序号），避免无关块被拼进同一份"资料"
        Map<String, List<RetrievedChunk>> byDoc = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk retrievedChunk : retrievedChunkList) {
            String docId = docIdOf(retrievedChunk);
            String key = docId != null ? docId : "__nodoc__" + (anonymousSeq++);
            byDoc.computeIfAbsent(key, k -> new ArrayList<>()).add(retrievedChunk);
        }
        StringBuilder sb = new StringBuilder("<documents>\n");
        List<Citation> citations = new ArrayList<>(byDoc.size());
        Map<String, String> sourceLocations = loadSourceLocations(byDoc.keySet());
        int idx = 1;
        for (Map.Entry<String, List<RetrievedChunk>> entry : byDoc.entrySet()) {
            List<RetrievedChunk> group = entry.getValue();
            group.sort(Comparator.comparingInt(RagContextAssembler::chunkIndexOf));
            sb.append("<content ref=\"").append(idx).append("\">\n");
            for (RetrievedChunk c : group) {
                sb.append(c.getContent() == null ? "" : c.getContent()).append("\n");
            }
            sb.append("</content>\n");
            String docId = entry.getKey();
            citations.add(buildCitation(idx, docId, group, sourceLocations));
            idx++;
        }
        return new RagContext(sb.append("</documents>").toString(), byDoc.size(), citations);
    }

    /** 组装单条引用溯源项（docKey 为 doc_id 或 __nodoc__+序号） */
    private Citation buildCitation(int ref, String docKey, List<RetrievedChunk> group,
                                   Map<String, String> sourceLocations) {
        String docId = docKey.startsWith("__nodoc__") ? null : docKey;
        String preview = TextPreviews.preview(group.get(0).getContent(), 240);
        return new Citation(ref, docId, docNameOf(group.get(0)), group.size(), preview,
                docId != null ? sourceLocations.get(docId) : null);
    }

    /** 批量预查文档公开预览 URL（文档目录 SPI，一次 IN 免逐文档 N+1）；查询失败不影响回答主链路 */
    private Map<String, String> loadSourceLocations(Set<String> docKeys) {
        List<String> docIds = docKeys.stream().filter(k -> !k.startsWith("__nodoc__")).toList();
        if (docIds.isEmpty()) {
            return Map.of();
        }
        try {
            return documentCatalog.sourceLocations(docIds);
        } catch (Exception e) {
            log.warn("[RagContext] 批量查询引用文档原文链接失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** chunk 来源文档名（doc_name 元数据；与 MultiChannelRetrievalEngine 日志口径一致） */
    private static String docNameOf(RetrievedChunk c) {
        Object v = c.getMetadata() == null ? null : c.getMetadata().get("doc_name");
        return v != null ? String.valueOf(v) : "未知文档";
    }

    /** chunk 在文档内的阅读序号（metadata.chunk_index），缺失按 0 排最前 */
    private static int chunkIndexOf(RetrievedChunk c) {
        Object v = c.getMetadata() == null ? null : c.getMetadata().get("chunk_index");
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** chunk 来源文档 ID（doc_id 元数据） */
    private static String docIdOf(RetrievedChunk retrievedChunk) {
        Map<String, Object> meta = retrievedChunk.getMetadata();
        if (meta == null) {
            return null;
        }
        Object v = meta.get("doc_id");
        return v == null ? null : v.toString();
    }
}
