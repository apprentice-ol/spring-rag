package com.nageoffer.ai.rag.ingestion.engine.chunk;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.ChunkingMode;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.ChunkingOptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.config.properties.IngestionProperties;
import com.nageoffer.ai.rag.ingestion.engine.IngestionContext;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionNodeType;
import com.nageoffer.ai.rag.ingestion.engine.IngestionNode;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.NodeResult;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 分块节点（搬原 ragent，删 ChunkEmbeddingService —— 嵌入交给 IndexerNode 的 vectorStore.add 自动完成）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final StructuredChunkingService structuredChunkingService;
    private final IngestionProperties ingestionProperties;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        ChunkerSettings settings = parseSettings(config.getSettings());

        // 运行时路线 override（上传时手选）：纯文本路线丢弃结构化 blocks，强制走 legacy 边界切分
        // （StructuredChunkingService 在 blocks 为空时 fallback 到 StructureAwareTextChunker，不保 block 元数据）；
        // null/false=语义感知，blocks 正常 → block-aware（保留 HEADING/TABLE/LIST 等结构元数据）。
        boolean plainText = Boolean.TRUE.equals(context.getPlainTextChunking());
        List<Block> blocks = plainText ? null
                : (context.getDocument() == null ? null : context.getDocument().getBlocks());
        boolean hasBlocks = blocks != null && !blocks.isEmpty();
        String text = StringUtils.hasText(context.getEnhancedText())
                ? context.getEnhancedText()
                : context.getRawText();
        ChunkingMode mode = plainText ? ChunkingMode.STRUCTURE_AWARE : settings.getStrategy();
        ChunkingOptions options = mode.createDefaultOptions(settings.getChunkSize(), settings.getOverlapSize());

        List<VectorChunk> chunks = structuredChunkingService.chunk(
                blocks, text, mode, options, settings.getRowsPerChunk());

        if (chunks.isEmpty()) {
            return NodeResult.fail(new ClientException(hasBlocks ? "分块结果为空" : "可分块文本为空"));
        }

        // 嵌入交给 IndexerNode 的 vectorStore.add（VectorChunk.embedding 不再在此填充）
        context.setChunks(chunks);
        String path = plainText ? "legacy-text(plain)" : (hasBlocks ? "block-aware" : "legacy-text");
        return NodeResult.ok("已分块 " + chunks.size() + " 段, path=" + path);
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        int defaultChunkSize = ingestionProperties.getChunkSize();
        int defaultOverlap = ingestionProperties.getChunkOverlap();
        if (node == null || node.isNull()) {
            return ChunkerSettings.builder()
                    .strategy(ChunkingMode.STRUCTURE_AWARE)
                    .chunkSize(defaultChunkSize)
                    .overlapSize(defaultOverlap)
                    .build();
        }
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        // 固定大小分块已暂时屏蔽：统一改用结构感知（避免硬切代码/表格，块边界更自然）
        if (settings.getStrategy() == ChunkingMode.FIXED_SIZE) {
            log.warn("[Chunker] FIXED_SIZE 固定分块已暂时屏蔽，自动改用 STRUCTURE_AWARE 结构感知");
            settings.setStrategy(ChunkingMode.STRUCTURE_AWARE);
        }
        if (settings.getStrategy() == null) {
            settings.setStrategy(ChunkingMode.STRUCTURE_AWARE);
        }
        Integer chunkSize = settings.getChunkSize();
        if (chunkSize == null
                || (chunkSize <= 0 && chunkSize != StructuredChunkingService.WHOLE_DOCUMENT_SENTINEL)) {
            settings.setChunkSize(defaultChunkSize);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(defaultOverlap);
        }
        return settings;
    }
}
