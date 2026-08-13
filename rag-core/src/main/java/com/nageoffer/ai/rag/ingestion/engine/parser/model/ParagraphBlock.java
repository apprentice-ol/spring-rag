package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;

/** 段落块（纯文本，不含 Markdown 标记）。 */
public record ParagraphBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        String text) implements Block {
}
