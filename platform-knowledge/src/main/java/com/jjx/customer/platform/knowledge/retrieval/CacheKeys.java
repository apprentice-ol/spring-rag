package com.jjx.customer.platform.knowledge.retrieval;

import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 缓存 key 构造（SHA-256 截断 24 hex，避免 32 位 hashCode 碰撞）。
 * <p><b>硬约束</b>：{@link #retrievalKey} 必须包含 SearchContext 里全部影响检索结果的参数
 * （query/rewrittenQuery/topK/threshold/budget 三值/collectionId/restrictedDocIds/metadata 的
 * intent 与 needsWebSearch）——eval 参数扫描靠同一引擎跑不同参数，key 缺任何一项就会把
 * A 参数的结果错配给 B 参数，破坏评测语义。<b>配置级参数</b>（rerank 阈值、通道开关等）
 * 不在 SearchContext 内，无法进 key，由 TTL 兜底（分钟级）。
 */
public final class CacheKeys {

    public static final String PREFIX_INTENT = "rag:cache:intent:";
    public static final String PREFIX_RETRIEVAL = "rag:cache:retr:";
    public static final String PREFIX_LOGS = "rag:cache:logs:";
    public static final String PREFIX_ANSWER = "rag:cache:ans:";
    public static final String PREFIX_EMBEDDING = "rag:cache:emb:";
    public static final String PREFIX_DOCVER = "rag:cache:docver:";
    /** 全部缓存键的公共前缀（SCAN 聚合用） */
    public static final String PREFIX_ALL = "rag:cache:";

    /** key 各部分的分隔符（SOH 控制符 = (char)1，正常业务文本不出现，避免部分拼接歧义） */
    private static final char PART_SEPARATOR = (char) 1;

    private CacheKeys() {
    }

    /** 各部分以不可见分隔符拼接后 SHA-256 截断（null 统一编码为 §null§，避免 null/空串同 key）。 */
    public static String hash(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(PART_SEPARATOR);
            }
            sb.append(parts[i] == null ? "§null§" : parts[i]);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必带 SHA-256，理论不可达
            throw new IllegalStateException(e);
        }
    }

    /** 意图缓存口径版本：分类输出结构变化（2026-09-12 去域感知：intent→domain）时 bump，
     *  旧口径条目 key 不同自然失效。 */
    private static final String INTENT_SCHEMA_VERSION = "v2-domain";

    /** 意图分类：入参应为规则归一化后的问题（pipeline 传入即已归一）。 */
    public static String intentKey(String normalizedQuestion) {
        return PREFIX_INTENT + hash(INTENT_SCHEMA_VERSION, normalizedQuestion);
    }

    /** 检索结果：SearchContext 全参数 + 文档版本号（见类注释的硬约束）。 */
    public static String retrievalKey(SearchContext ctx, long docver) {
        List<String> parts = new ArrayList<>();
        parts.add(ctx.getQuery());
        parts.add(ctx.getRewrittenQuery());
        parts.add(String.valueOf(ctx.getTopK()));
        parts.add(String.valueOf(ctx.getThreshold()));
        RetrievalBudget b = ctx.getBudget();
        if (b != null) {
            parts.add(b.getRecallBudget() + "/" + b.getCandidateLimit() + "/" + b.getContextTopK());
        } else {
            parts.add("no-budget");
        }
        parts.add(ctx.getCollectionId() == null ? "_all" : String.valueOf(ctx.getCollectionId()));
        parts.add(ctx.getRestrictedDocIds() == null ? "no-restrict"
                : ctx.getRestrictedDocIds().stream().sorted().toList().toString());
        Map<String, Object> meta = ctx.getMetadata();
        if (meta != null) {
            parts.add(String.valueOf(meta.get("intent")));
            parts.add(String.valueOf(meta.get("needsWebSearch")));
        } else {
            parts.add("no-meta");
            parts.add("no-meta");
        }
        parts.add(String.valueOf(docver));
        return PREFIX_RETRIEVAL + hash(parts.toArray(new String[0]));
    }

    /** 日志查询：绝对时间窗（毫秒，须先落分钟桶）+ 过滤参数；trace_id 精查时时间参数传 0。 */
    public static String logsKey(String traceId, long startMs, long endMs, String keyword, String level, int limit) {
        return PREFIX_LOGS + hash(traceId, String.valueOf(startMs), String.valueOf(endMs),
                keyword, level, String.valueOf(limit));
    }

    /** 精确答案：问题 + agent 范式 + 文档版本号 + prompt 内容指纹
     *  （换范式/重灌文档/改任一层 prompt 自然失效——不变量 5 的缓存侧落点）。 */
    public static String answerKey(String question, String paradigm, long docver, String promptHash) {
        return PREFIX_ANSWER + hash(question, paradigm, String.valueOf(docver), promptHash);
    }

    /** 查询 embedding：模型标识 + 查询文本。 */
    public static String embeddingKey(String modelTag, String query) {
        return PREFIX_EMBEDDING + hash(modelTag, query);
    }

    /** 文档版本号键（collectionId 的字符串形式，全库哨兵 "_all"）。 */
    public static String docverKey(String collectionKey) {
        return PREFIX_DOCVER + collectionKey;
    }
}
