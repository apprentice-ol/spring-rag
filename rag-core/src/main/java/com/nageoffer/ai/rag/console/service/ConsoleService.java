package com.nageoffer.ai.rag.console.service;

import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.nageoffer.ai.rag.console.domain.ConsoleOverview;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 控制台总览聚合：系统状态（探测）+ 业务统计（COUNT 各表）+ 检索/入库配置（只读）。
 *
 * <p>统计走 JdbcTemplate 直查（不依赖各业务 Mapper），表缺失返回 -1 不报错。
 * 配置读 ChatProperties + @Value，纯只读展示。
 * 6 个 COUNT 虚拟线程并行 + 向量大表用 reltuples 估算 + 60s 结果缓存（总览允许秒级陈旧，
 * 此前 6 次串行 DB 往返，且 pgvector 大表 COUNT(*) 是全表扫描）。</p>
 */
@Slf4j
@Service
public class ConsoleService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatProperties chatProperties;

    /** 总览缓存（60s；免每次打开控制台都全表 COUNT） */
    private final AtomicReference<CachedOverview> cache = new AtomicReference<>();

    @Value("${rag.search.fusion.rrf-k:60}")
    private int rrfK;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.search.channels.keyword.enabled:false}")
    private boolean keywordEnabled;

    @Value("${rag.search.channels.web-search.enabled:false}")
    private boolean webSearchEnabled;

    @Value("${rag.ingestion.chunk-size:512}")
    private int chunkSize;

    @Value("${rag.ingestion.chunk-overlap:200}")
    private int chunkOverlap;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String chatModel;

    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModel;

    @Value("${rag.rerank.bailian.model:}")
    private String rerankModel;

    @Value("${spring.application.name:}")
    private String appName;

    public ConsoleService(JdbcTemplate jdbcTemplate, ChatProperties chatProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatProperties = chatProperties;
    }

    public ConsoleOverview overview() {
        CachedOverview cached = cache.get();
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        ConsoleOverview fresh = loadOverview();
        cache.set(new CachedOverview(fresh, System.currentTimeMillis() + CACHE_TTL_MS));
        return fresh;
    }

    private ConsoleOverview loadOverview() {
        String database = "online";
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            database = "offline";
            log.warn("[Console] 数据库探测失败: {}", e.getMessage());
        }

        ConsoleOverview.SystemStatus system = new ConsoleOverview.SystemStatus(
                "online", database, System.getProperty("java.version"), appName);

        // 5 个 COUNT 并行（虚拟线程）；向量大表单独走估算
        CompletableFuture<Long> documents = CompletableFuture.supplyAsync(() -> count("sa_document"));
        CompletableFuture<Long> vectors = CompletableFuture.supplyAsync(this::estimateVectorCount);
        CompletableFuture<Long> conversations = CompletableFuture.supplyAsync(() -> count("sa_conversation"));
        CompletableFuture<Long> evalRuns = CompletableFuture.supplyAsync(() -> count("sa_eval_run"));
        CompletableFuture<Long> evalDatasets = CompletableFuture.supplyAsync(() -> count("sa_eval_dataset"));
        CompletableFuture.allOf(documents, vectors, conversations, evalRuns, evalDatasets).join();

        ConsoleOverview.Stats stats = new ConsoleOverview.Stats(
                documents.join(), vectors.join(), conversations.join(), evalRuns.join(), evalDatasets.join());

        ConsoleOverview.RetrievalConfig config = new ConsoleOverview.RetrievalConfig(
                chatProperties.getTopK(),
                chatProperties.getSimilarityThreshold(),
                chatProperties.getRecallBudget(),
                chatProperties.getCandidateLimit(),
                chatProperties.getContextTopK(),
                rrfK, chunkSize, chunkOverlap,
                rerankEnabled, keywordEnabled, webSearchEnabled,
                chatModel, embeddingModel, rerankModel);

        return new ConsoleOverview(system, stats, config);
    }

    /** 总览缓存时长 */
    private static final long CACHE_TTL_MS = 60_000;

    private record CachedOverview(ConsoleOverview value, long expiresAtMillis) {
        boolean expired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    private long count(String table) {
        try {
            Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 向量表行数估算（pg_class.reltuples，ANALYZE 统计）：
     * pgvector 表 COUNT(*) 是全表扫描，几十万向量后秒级；总览场景估算值足够。
     * reltuples 为 -1（从未 ANALYZE）时回退精确 COUNT。
     */
    private long estimateVectorCount() {
        try {
            Long estimate = jdbcTemplate.queryForObject(
                    "SELECT reltuples::bigint FROM pg_class WHERE relname = 'spring_ai_store_vector'", Long.class);
            if (estimate == null) {
                return count("spring_ai_store_vector");
            }
            if (estimate < 0) {
                return count("spring_ai_store_vector");
            }
            return estimate;
        } catch (Exception e) {
            return count("spring_ai_store_vector");
        }
    }
}
