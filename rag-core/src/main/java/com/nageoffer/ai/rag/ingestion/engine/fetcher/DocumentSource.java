package com.nageoffer.ai.rag.ingestion.engine.fetcher;

import com.nageoffer.ai.rag.ingestion.engine.enums.SourceType;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** 文档来源（FILE 上传字节 / URL 远程抓取）。 */
@Data
@Builder
public class DocumentSource {

    private SourceType type;
    private String location;
    private String fileName;
    private Map<String, String> credentials;
}
