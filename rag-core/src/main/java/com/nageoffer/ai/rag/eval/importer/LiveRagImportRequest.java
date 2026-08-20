package com.nageoffer.ai.rag.eval.importer;

/**
 * LiveRAG 导入请求。全部字段可空，缺省取 {@link com.nageoffer.ai.rag.eval.config.EvalProperties.LiveRag} 配置。
 */
public record LiveRagImportRequest(
        /** 抽样题数（如 50；空 → 配置 sampleSize） */
        Integer sampleSize,
        /**
         * 是否强制重新下载 parquet（默认 false：缓存文件存在即复用）
         * */
        Boolean forceRefresh,
        /** 目标数据集名（默认 "LiveRAG"，同名已存在则复用、条目按 item_key 幂等追加） */
        String datasetName,
        /**
         * 语料归入的文档集合（sa_doc_collection）ID。
         * 空 → 自动按数据集名查/建同名集合：语料入库时即带 collection_id
         * （向量 metadata 与 sa_document 同步写对，文件管理页可见归属，检索范围隔离直接生效）；
         * 指定 → 校验存在后归入该集合（可指向已有的 LiveRAG 语料集合）。
         */
        Long collectionId) {
}
