package com.jjx.customer.platform.ingestion.engine.parser.model;

import java.util.List;

/** 代码块（language 可空，如 java/bash）。 */
public record CodeBlock(
        String id,
        /**
         * 代码块的来源信息。
         */
        Provenance provenance,
        /**
         * 代码块的目录路径。
         */
        List<String> outlinePath,
        /**
         * 代码块的编程语言。
         */
        String language,
        /**
         * 代码块的内容。
         */
        String code) implements Block {
}
