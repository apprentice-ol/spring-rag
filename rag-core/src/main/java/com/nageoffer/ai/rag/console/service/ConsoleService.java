package com.nageoffer.ai.rag.console.service;

import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.nageoffer.ai.rag.console.domain.ConsoleOverview;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 控制台总览聚合：系统状态（探测）+ 业务统计（COUNT 各表）+ 检索/入库配置（只读）。
 *
 * <p>统计走 JdbcTemplate 直查（不依赖各业务 Mapper），表缺失返回 -1 不报错。
 * 配置读 ChatProperties + @Value，纯只读展示。</p>
 */
@Slf4j
@Service
public class ConsoleService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatProperties chatProperties;

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
        String database = "online";
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            database = "offline";
            log.warn("[Console] 数据库探测失败: {}", e.getMessage());
        }

        ConsoleOverview.SystemStatus system = new ConsoleOverview.SystemStatus(
                "online", database, System.getProperty("java.version"), appName);

        ConsoleOverview.Stats stats = new ConsoleOverview.Stats(
                count("sa_document"),
                count("spring_ai_store_vector"),
                count("sa_conversation"),
                count("sa_eval_run"),
                count("sa_eval_dataset"));

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

    private long count(String table) {
        try {
            Long c = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return -1;
        }
    }
}
