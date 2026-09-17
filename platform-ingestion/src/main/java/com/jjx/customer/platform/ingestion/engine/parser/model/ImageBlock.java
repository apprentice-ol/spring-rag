package com.jjx.customer.platform.ingestion.engine.parser.model;

import java.util.List;

/** 图片块（asset 引用 RustFS；description 是 VLM 生成的图片描述，P6 填充）。 */
public record ImageBlock(
        /**
         * 图片块的唯一标识符。
         */
        String id,
        /**
         * 图片块的来源信息。
         */
        Provenance provenance,
        /**
         * 图片块的目录路径。
         */
        List<String> outlinePath,
        /**
         * 图片块的资产引用。
         */
        AssetRef asset,
        /**
         * 图片块的标题。
         */
        String caption,
        /**
         * 图片块的替代文本。
         */
        String description) implements Block {
}
