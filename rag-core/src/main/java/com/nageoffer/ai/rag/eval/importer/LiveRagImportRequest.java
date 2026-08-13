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
        String datasetName) {
}
