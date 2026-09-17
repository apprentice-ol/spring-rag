package com.jjx.customer.platform.ingestion.engine.parser.model;

import java.util.List;

/** 列表块（ordered 区分有序/无序，items 为列表项文本）。 */
public record ListBlock(
        /**
         * 列表块的唯一标识符。
         */
        String id,
        /**
         * 列表块的来源信息。
         */
        Provenance provenance,
        /**
         * 列表块的目录路径。
         */
        List<String> outlinePath,
        /**
         * 列表块是否有序。
         */
        boolean ordered,
        /**
         * 列表块的列表项文本。
         */
        List<String> items) implements Block {
}
