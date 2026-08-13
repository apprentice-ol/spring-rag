package com.nageoffer.ai.rag.ingestion.engine;

import com.nageoffer.ai.rag.ingestion.engine.fetcher.DocumentSource;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionNodeType;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 节点输出提取器（搬自原 ragent，改 package）。
 *
 * <p>按节点类型从 IngestionContext 提取关键输出（写入 NodeLog.output，便于排查/前端展示）。
 */
@Component
public class NodeOutputExtractor {

    public Map<String, Object> extract(IngestionContext context, NodeConfig config) {
        if (context == null || config == null) {
            return Map.of();
        }
        IngestionNodeType nodeType = resolveNodeType(config.getNodeType());
        if (nodeType == null) {
            return genericOutput(context);
        }
        return switch (nodeType) {
            case FETCHER -> fetcherOutput(context);
            case PARSER -> parserOutput(context);
            case ENHANCER -> enhancerOutput(context);
            case CHUNKER -> chunkerOutput(context);
            case ENRICHER -> enricherOutput(context);
            case INDEXER -> indexerOutput(context, config);
        };
    }

    private Map<String, Object> fetcherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        DocumentSource source = context.getSource();
        if (source != null) {
            Map<String, Object> sourceView = new LinkedHashMap<>();
            sourceView.put("type", source.getType() == null ? null : source.getType().getValue());
            sourceView.put("location", source.getLocation());
            sourceView.put("fileName", source.getFileName());
            output.put("source", sourceView);
        }
        output.put("mimeType", context.getMimeType());
        byte[] raw = context.getRawBytes();
        if (raw != null) {
            output.put("rawBytesLength", raw.length);
        }
        return output;
    }

    private Map<String, Object> parserOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawTextLength", context.getRawText() == null ? 0 : context.getRawText().length());
        return output;
    }

    private Map<String, Object> enhancerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("keywords", context.getKeywords());
        output.put("questions", context.getQuestions());
        return output;
    }

    private Map<String, Object> chunkerOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        return output;
    }

    private Map<String, Object> enricherOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        return output;
    }

    private Map<String, Object> indexerOutput(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("settings", config.getSettings());
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        return output;
    }

    private Map<String, Object> genericOutput(IngestionContext context) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawTextLength", context.getRawText() == null ? 0 : context.getRawText().length());
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        return output;
    }

    private IngestionNodeType resolveNodeType(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return null;
        }
        try {
            return IngestionNodeType.fromValue(nodeType);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
