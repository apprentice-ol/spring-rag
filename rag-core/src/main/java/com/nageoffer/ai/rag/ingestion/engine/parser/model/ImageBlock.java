package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import java.util.List;

/** 图片块（asset 引用 RustFS；description 是 VLM 生成的图片描述，P6 填充）。 */
public record ImageBlock(
        String id,
        Provenance provenance,
        List<String> outlinePath,
        AssetRef asset,
        String caption,
        String altText,
        String description) implements Block {
}
