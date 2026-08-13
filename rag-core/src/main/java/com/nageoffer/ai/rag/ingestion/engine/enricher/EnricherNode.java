package com.nageoffer.ai.rag.ingestion.engine.enricher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.IngestionContext;
import com.nageoffer.ai.rag.ingestion.engine.enums.ChunkEnrichType;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionNodeType;
import com.nageoffer.ai.rag.ingestion.engine.IngestionNode;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.NodeResult;
import com.nageoffer.ai.rag.common.util.JsonResponseParser;
import com.nageoffer.ai.rag.common.util.PromptTemplateRenderer;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * chunk 级富集节点（搬原 ragent，LLMService→ingestionChatClient）。
 *
 * <p>3 任务（逐 chunk 调用）：KEYWORDS（→chunk.metadata.keywords）/ SUMMARY（→summary）/ METADATA（→metadata）。
 *
 * <p><b>并行富集</b>（对齐 ragent 入库速度）：按 chunk 并行调 LLM（每 chunk 内任务串行），
 * 并发度 {@link #ENRICH_CONCURRENCY}=5 与 MinerU 解析并发一致，避免打爆限流。
 */
@Component
@Slf4j
public class EnricherNode implements IngestionNode {

    /** 富集 LLM 并发度（与 MinerU 解析并发对齐，避免 DeepSeek/百炼限流） */
    private static final int ENRICH_CONCURRENCY = 5;

    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final PromptStore promptStore;

    /** 各富集任务类型 → 资源文件中 system prompt 的 key（DB 配置未覆盖时回退到此）。 */
    private static final Map<ChunkEnrichType, String> SYSTEM_PROMPT_KEYS = Map.of(
            ChunkEnrichType.KEYWORDS, "ingestion/enricher/chunk-keywords",
            ChunkEnrichType.SUMMARY, "ingestion/enricher/chunk-summary",
            ChunkEnrichType.METADATA, "ingestion/enricher/chunk-metadata");

    public EnricherNode(ObjectMapper objectMapper,
                        @Qualifier("ingestionChatClient") ChatClient chatClient,
                        PromptStore promptStore) {
        this.objectMapper = objectMapper;
        this.chatClient = chatClient;
        this.promptStore = promptStore;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.ENRICHER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("无 chunks 可富集");
        }
        EnricherSettings settings = parseSettings(config.getSettings());
        if (settings.getTasks() == null || settings.getTasks().isEmpty()) {
            settings.setTasks(List.of(EnricherSettings.ChunkEnrichTask.builder()
                    .type(ChunkEnrichType.KEYWORDS).build()));
            log.info("[Enricher] 未配置富集任务，使用默认 KEYWORDS");
        }
        boolean attachMetadata = settings.getAttachDocumentMetadata() == null || settings.getAttachDocumentMetadata();

        // 按 chunk 并行富集（每 chunk 内任务仍串行保持顺序）：逐块串行 LLM 调用是入库耗时主瓶颈
        // （49 块 × 2 任务 ≈ 150s），并发后降到单块耗时 × 任务数 / 并发度。
        // ChatClient 线程安全；不同 chunk 的 metadata 互不共享，并行写安全。
        // 虚拟线程无需固定池大小，用 Semaphore 控制并发度避免打爆 LLM 限流。
        Semaphore concurrencyLimiter = new Semaphore(ENRICH_CONCURRENCY);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (VectorChunk chunk : chunks) {
                if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                    continue;
                }
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        concurrencyLimiter.acquire();
                        if (chunk.getMetadata() == null) {
                            chunk.setMetadata(new HashMap<>());
                        }
                        if (attachMetadata && context.getMetadata() != null) {
                            chunk.getMetadata().putAll(context.getMetadata());
                        }
                        for (EnricherSettings.ChunkEnrichTask task : settings.getTasks()) {
                            if (task == null || task.getType() == null) {
                                continue;
                            }
                            ChunkEnrichType type = task.getType();
                            String systemPrompt = promptStore.rawOrOverride(
                                    SYSTEM_PROMPT_KEYS.get(type), task.getSystemPrompt());
                            String userPrompt = buildUserPrompt(task.getUserPromptTemplate(), chunk, context);
                            String response = this.chat(systemPrompt, userPrompt);
                            applyResult(chunk, type, response);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("富集任务被中断", e);
                    } finally {
                        concurrencyLimiter.release();
                    }
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        return NodeResult.ok("富集完成");
    }

    private EnricherSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return EnricherSettings.builder().tasks(List.of()).build();
        }
        return objectMapper.convertValue(node, EnricherSettings.class);
    }

    private String buildUserPrompt(String template, VectorChunk chunk, IngestionContext context) {
        String input = chunk.getContent();
        if (!StringUtils.hasText(template)) {
            return input;
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("text", input);
        vars.put("content", input);
        vars.put("chunkIndex", chunk.getIndex());
        vars.put("taskId", context.getTaskId());
        vars.put("pipelineId", context.getPipelineId());
        return PromptTemplateRenderer.render(template, vars);
    }

    private String chat(String systemPrompt, String userPrompt) {
        return chatClient.prompt()
                .system(systemPrompt == null ? "" : systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    private void applyResult(VectorChunk chunk, ChunkEnrichType type, String response) {
        switch (type) {
            case KEYWORDS -> chunk.getMetadata().put("keywords", JsonResponseParser.parseStringList(response));
            case SUMMARY -> chunk.getMetadata().put("summary",
                    StringUtils.hasText(response) ? response.trim() : response);
            case METADATA -> chunk.getMetadata().putAll(JsonResponseParser.parseObject(response));
            default -> {
            }
        }
    }
}
