package com.nageoffer.ai.rag.ingestion.engine.parser.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * 文档块（sealed interface + 6 子类，搬原 ragent core/parser/model/Block）。
 *
 * <p>Block 是结构化解析的统一中间表示：PDF/Word(MinerU)/Markdown/HTML 解析后都产出 Block 列表，
 * 供 Block-Aware 分块（P3）按类型（标题/段落/表格/图片/代码/列表）差异化切分。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = HeadingBlock.class, name = "heading"),
        @JsonSubTypes.Type(value = ParagraphBlock.class, name = "paragraph"),
        @JsonSubTypes.Type(value = TableBlock.class, name = "table"),
        @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
        @JsonSubTypes.Type(value = CodeBlock.class, name = "code"),
        @JsonSubTypes.Type(value = ListBlock.class, name = "list")
})
public sealed interface Block permits HeadingBlock, ParagraphBlock, TableBlock, ImageBlock, CodeBlock, ListBlock {

    String id();

    Provenance provenance();

    List<String> outlinePath();
}
