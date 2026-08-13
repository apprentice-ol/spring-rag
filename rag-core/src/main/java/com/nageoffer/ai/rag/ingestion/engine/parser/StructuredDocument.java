package com.nageoffer.ai.rag.ingestion.engine.parser;

import com.nageoffer.ai.rag.ingestion.engine.parser.model.Block;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** 解析后的结构化文档（ParserNode 产出）。 */
@Data
@Builder
public class StructuredDocument {

    private String text;
    private List<Block> blocks;
    private Map<String, Object> metadata;
}
