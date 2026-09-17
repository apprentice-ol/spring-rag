package com.jjx.customer.platform.ingestion.engine.parser.model;

import java.util.List;

/** 表格块（headers + rows 已展平合并单元格 + captionText 标题）。 */
public record TableBlock(
        /**
         * 表格块的唯一标识符。
         */
        String id,
        /**
         * 表格块的来源信息。
         */
        Provenance provenance,
        /**
         * 表格块的目录路径。
         */
        List<String> outlinePath,
        /**
         * 表格块的表头。
         */
        List<String> headers,
        /**
         * 表格块的行数据。
         */
        List<List<String>> rows,
        /**
         * 表格块的标题。
         */
        String captionText) implements Block {
}
