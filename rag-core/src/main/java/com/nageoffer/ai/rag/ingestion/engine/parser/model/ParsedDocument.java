package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;
import java.util.Map;

/** 解析结果（Block 列表 + 元数据，ParserNode 产出）。 */
public record ParsedDocument(
        List<Block> blocks,
        Map<String, Object> metadata) {

    public static ParsedDocument of(List<Block> blocks, Map<String, Object> metadata) {
        return new ParsedDocument(blocks, metadata);
    }

    /** 空元数据便捷重载（对应原 ragent ParsedDocument.of(List)）。 */
    public static ParsedDocument of(List<Block> blocks) {
        return new ParsedDocument(blocks, Map.of());
    }
}
