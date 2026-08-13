package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;

/** 标题块（level 1-6 对应 Markdown # ~ ######）。 */
public record HeadingBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        int level,
        String text) implements Block {
}
