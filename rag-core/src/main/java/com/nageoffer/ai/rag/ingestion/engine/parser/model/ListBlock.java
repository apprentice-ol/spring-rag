package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;

/** 列表块（ordered 区分有序/无序，items 为列表项文本）。 */
public record ListBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        boolean ordered,
        List<String> items) implements Block {
}
