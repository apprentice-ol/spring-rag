package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;

/** 表格块（headers + rows 已展平合并单元格 + captionText 标题）。 */
public record TableBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        List<String> headers,
        List<List<String>> rows,
        String captionText) implements Block {
}
