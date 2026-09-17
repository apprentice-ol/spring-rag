package com.jjx.customer.platform.document;

import java.util.List;
import java.util.Map;

/**
 * 文档目录（SPI）：库内文档的只读视图，供编排层做"先行对象"判定等决策。
 *
 * <p>方向：business（编排）依赖本接口；platform-ingestion 提供实现（sa_document）。</p>
 */
public interface DocumentCatalog {

    /** 库内全部文档名（不含路径）；无文档返回空表。 */
    List<String> allDocumentNames();

    /** 批量取文档原文公开链接（docId → source_location）：引用溯源面板用；查不到的不出现在结果里。 */
    Map<String, String> sourceLocations(List<String> docIds);
}
