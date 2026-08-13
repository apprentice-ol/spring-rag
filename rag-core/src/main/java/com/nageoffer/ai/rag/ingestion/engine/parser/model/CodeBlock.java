package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;

/** 代码块（language 可空，如 java/bash）。 */
public record CodeBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        String language,
        String code) implements Block {
}
