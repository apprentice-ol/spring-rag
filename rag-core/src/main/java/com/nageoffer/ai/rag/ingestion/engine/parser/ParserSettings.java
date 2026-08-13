package com.nageoffer.ai.rag.ingestion.engine.parser;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 解析器设置。
 * <p>定义文档解析节点的配置参数。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParserSettings {

    /** 解析规则列表，按 MIME 类型匹配解析器 */
    private List<ParserRule> rules;

    /**
     * MinerU 结果解包路线：{@code markdown}（默认，走 full.md + commonmark）/ {@code content_list}（走
     * content_list.json 结构化，表格解析成 TableBlock）。仅对 MinerU 解析器生效，null 时按 markdown。
     * <p>透传路径：pipeline parser 节点 settings → ParserNode 注入 options → MinerUDocumentParser → MinerUResultUnpacker
     */
    private String unpackMode;

    /** 单个解析规则。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ParserRule {

        /** 文档类型（如 application/pdf、text/markdown） */
        private String mimeType;

        /** 解析器额外配置选项 */
        private Map<String, Object> options;
    }
}
