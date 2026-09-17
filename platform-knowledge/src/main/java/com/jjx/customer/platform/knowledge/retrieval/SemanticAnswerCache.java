package com.jjx.customer.platform.knowledge.retrieval;
import com.jjx.customer.platform.cache.*;

import com.jjx.customer.platform.knowledge.retrieval.QueryEmbedder;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 语义答案缓存（pgvector）：同义不同字面的问题经 embedding 相似度命中，直接重放缓存的答案——
 * exact 缓存（字符串级）的上一层。命中条件：同范式 + 同文档版本（docver 进 WHERE，
 * 重灌自然失配）+ cosine 相似度 ≥ {@code rag.cache.semantic.threshold}（0.95 起步，
 * 收很紧防"相似≠同义"误命中——"怎么冲红"与"冲错了怎么撤销"向量距离很近但答案不同）。
 *
 * <p>失效与容量：无反馈机制（点踩明确不做），全靠 docver WHERE + 概率清理
 * （store 后 1% 概率删过期行并按容量裁剪最旧行）。语义写入刻意不做频率准入：
 * 字面不同的重复问题正是语义缓存的靶子，exact 的准入挡不住它们；写入成本
 * 仅一次 embedding（Redis embedding 缓存命中时近零）。
 *
 * <p>全操作吞异常：语义缓存是增强层，任何失败都当 miss，绝不阻断回答主链路。
 */
@Slf4j
@Component
public class SemanticAnswerCache {

    /** 概率清理的触发概率（每次 store 后） */
    private static final double CLEANUP_PROBABILITY = 0.01;

    private final JdbcTemplate jdbcTemplate;
    private final QueryEmbedder queryEmbedder;
    private final CacheProperties cacheProperties;
    private final DocumentVersionStamp docver;
    private final CacheMetrics cacheMetrics;

    public SemanticAnswerCache(JdbcTemplate jdbcTemplate,
                               QueryEmbedder queryEmbedder,
                               CacheProperties cacheProperties,
                               DocumentVersionStamp docver,
                               CacheMetrics cacheMetrics) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryEmbedder = queryEmbedder;
        this.cacheProperties = cacheProperties;
        this.docver = docver;
        this.cacheMetrics = cacheMetrics;
    }

    /** 语义命中载荷（messageId 用于命中后回写 exact 缓存与计数联动）。 */
    public record SemanticHit(String answer, String citationsJson, Long messageId) {
    }

    /**
     * 按相似问题查缓存答案。
     *
     * @param normalizedQuestion 归一化问题（与写入同源，管线传 ruleNormalized）
     * @param paradigm           agent 范式
     * @param promptHash         prompt 内容指纹（改任一层 prompt 自动失配，与 exact 层同口径）
     * @return 命中的答案与引用；未启用/embedding 失败/无相似行/相似度不足均返回 empty
     */
    public Optional<SemanticHit> lookup(String normalizedQuestion, String paradigm, String promptHash) {
        CacheProperties.Semantic props = cacheProperties.getSemantic();
        if (!cacheProperties.isEnabled() || !props.isEnabled()) {
            return Optional.empty();
        }
        try {
            String collectionKey = DocumentVersionStamp.ALL_COLLECTIONS; // 语义缓存以全库版本为失效轴
            long version = docver.current(collectionKey);
            if (version < 0) {
                return Optional.empty();
            }
            float[] vector = queryEmbedder.embed(normalizedQuestion);
            PGvector pgVector = new PGvector(vector);
            // top-1 相似（cosine 距离 → 相似度 = 1 - distance），WHERE 匹配范式、当前 docver 与 prompt 指纹
            List<Row> rows = jdbcTemplate.query(
                    "SELECT id, answer, citations, message_id, 1 - (question_embedding <=> ?::vector) AS sim "
                            + "FROM sa_cache_answer WHERE paradigm = ? AND docver = ? AND prompt_hash = ? "
                            + "ORDER BY question_embedding <=> ?::vector LIMIT 1",
                    (rs, i) -> new Row(rs.getLong("id"), rs.getString("answer"),
                            rs.getString("citations"), (Long) rs.getObject("message_id"), rs.getDouble("sim")),
                    pgVector, paradigm, version, promptHash, pgVector);
            if (rows.isEmpty() || rows.get(0).sim() < props.getThreshold()) {
                cacheMetrics.miss("semantic");
                return Optional.empty();
            }
            Row hit = rows.get(0);
            cacheMetrics.hit("semantic");
            bumpHitCount(hit.id());
            log.info("[语义缓存] 命中: sim={}, question=\"{}\"", String.format("%.4f", hit.sim()),
                    normalizedQuestion.length() > 60 ? normalizedQuestion.substring(0, 60) + "…" : normalizedQuestion);
            return Optional.of(new SemanticHit(hit.answer(), hit.citations(), hit.messageId()));
        } catch (Exception e) {
            log.debug("[语义缓存] 查询异常当 miss: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写入语义缓存行（answer 非空才写；citations 可 null）。
     * docver 取写入时点全库版本——文档重灌后新查询按新版本 WHERE，旧行自然失配等清理；
     * prompt_hash 同入行——改任一层 prompt 后按指纹 WHERE 自然失配。
     */
    public void store(String normalizedQuestion, String answer, String citationsJson,
                      String paradigm, String promptHash, Long messageId) {
        CacheProperties.Semantic props = cacheProperties.getSemantic();
        if (!cacheProperties.isEnabled() || !props.isEnabled() || answer == null || answer.isBlank()) {
            return;
        }
        try {
            long version = docver.current(DocumentVersionStamp.ALL_COLLECTIONS);
            if (version < 0) {
                return;
            }
            float[] vector = queryEmbedder.embed(normalizedQuestion);
            jdbcTemplate.update(
                    "INSERT INTO sa_cache_answer (question, question_embedding, answer, citations, paradigm, docver, prompt_hash, message_id) "
                            + "VALUES (?, ?::vector, ?, ?::jsonb, ?, ?, ?, ?)",
                    normalizedQuestion, new PGvector(vector), answer,
                    citationsJson != null ? citationsJson : null, paradigm, version, promptHash, messageId);
            maybeCleanup(props);
        } catch (Exception e) {
            log.debug("[语义缓存] 写入失败（忽略）: {}", e.getMessage());
        }
    }

    /** 热点问题榜（看板用）：按命中次数倒序取前 N 条缓存行。 */
    public List<TopQuestion> topQuestions(int limit) {
        try {
            return jdbcTemplate.query(
                    "SELECT question, paradigm, hit_count, update_time FROM sa_cache_answer "
                            + "ORDER BY hit_count DESC, update_time DESC LIMIT ?",
                    (rs, i) -> new TopQuestion(rs.getString("question"), rs.getString("paradigm"),
                            rs.getLong("hit_count"),
                            rs.getTimestamp("update_time") == null ? null
                                    : rs.getTimestamp("update_time").getTime()),
                    limit);
        } catch (Exception e) {
            log.debug("[语义缓存] 热点榜查询失败（忽略）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 热点问题行（看板视图）。 */
    public record TopQuestion(String question, String paradigm, long hitCount, Long updatedAt) {
    }

    /** 记录行（看板记录表用，answer 为全文——前端截断预览）。 */
    public record SemanticRecord(long id, String question, String answer, String paradigm,
                                 long docver, Long messageId, long hitCount, Long updateTime) {
    }

    /** 分页结果。 */
    public record RecordPage(long total, List<SemanticRecord> records) {
    }

    /** 分页取记录（update_time 倒序）。 */
    public RecordPage pageRecords(int page, int size) {
        try {
            Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sa_cache_answer", Long.class);
            int offset = Math.max(0, (page - 1)) * size;
            List<SemanticRecord> records = jdbcTemplate.query(
                    "SELECT id, question, answer, paradigm, docver, message_id, hit_count, update_time "
                            + "FROM sa_cache_answer ORDER BY update_time DESC LIMIT ? OFFSET ?",
                    (rs, i) -> new SemanticRecord(rs.getLong("id"), rs.getString("question"),
                            rs.getString("answer"), rs.getString("paradigm"), rs.getLong("docver"),
                            (Long) rs.getObject("message_id"), rs.getLong("hit_count"),
                            rs.getTimestamp("update_time") == null ? null
                                    : rs.getTimestamp("update_time").getTime()),
                    size, offset);
            return new RecordPage(total == null ? 0 : total, records);
        } catch (Exception e) {
            log.warn("[语义缓存] 记录分页查询失败: {}", e.getMessage());
            return new RecordPage(0, List.of());
        }
    }

    /** 删除单条记录（看板手动失效）。 */
    public boolean deleteById(long id) {
        try {
            return jdbcTemplate.update("DELETE FROM sa_cache_answer WHERE id = ?", id) > 0;
        } catch (Exception e) {
            log.warn("[语义缓存] 删除记录失败 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /** 清空全部记录。 */
    public int clearAll() {
        try {
            return jdbcTemplate.update("DELETE FROM sa_cache_answer");
        } catch (Exception e) {
            log.warn("[语义缓存] 清空失败: {}", e.getMessage());
            return 0;
        }
    }

    /** 命中计数（fire-and-forget，失败静默）。 */
    private void bumpHitCount(long id) {
        try {
            Thread.ofVirtual().name("semantic-hit-", 0).start(() -> {
                try {
                    jdbcTemplate.update(
                            "UPDATE sa_cache_answer SET hit_count = hit_count + 1, update_time = NOW() WHERE id = ?",
                            id);
                } catch (Exception ignore) {
                    // 计数失败无影响
                }
            });
        } catch (Exception ignore) {
            // 虚拟线程启动失败无影响
        }
    }

    /**
     * 按 messageId 累计命中（exact 层命中时联动：无论哪层命中，语义记录的命中次数都 +1，
     * 看板「命中」口径统一）。无对应行时静默（exact 缓存可能早于语义行或来自旧载荷）。
     */
    public void bumpByMessageId(Long messageId) {
        if (messageId == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "UPDATE sa_cache_answer SET hit_count = hit_count + 1, update_time = NOW() WHERE message_id = ?",
                    messageId);
        } catch (Exception e) {
            log.debug("[语义缓存] exact 命中联动计数失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 概率清理（零调度基建依赖）：删过期行 + 超量裁剪最旧。
     */
    private void maybeCleanup(CacheProperties.Semantic props) {
        if (ThreadLocalRandom.current().nextDouble() >= CLEANUP_PROBABILITY) {
            return;
        }
        try {
            int expired = jdbcTemplate.update(
                    "DELETE FROM sa_cache_answer WHERE update_time < NOW() - ?::interval",
                    props.getTtl().toSeconds() + " seconds");
            int trimmed = jdbcTemplate.update(
                    "DELETE FROM sa_cache_answer WHERE id IN ("
                            + "SELECT id FROM sa_cache_answer ORDER BY update_time DESC OFFSET ?)",
                    props.getMaxRows());
            if (expired > 0 || trimmed > 0) {
                log.info("[语义缓存] 清理: 过期 {} 行, 超量裁剪 {} 行（上限 {}）", expired, trimmed, props.getMaxRows());
            }
        } catch (Exception e) {
            log.debug("[语义缓存] 清理失败（忽略）: {}", e.getMessage());
        }
    }

    /** 查询行内部视图。 */
    private record Row(long id, String answer, String citations, Long messageId, double sim) {
    }
}
