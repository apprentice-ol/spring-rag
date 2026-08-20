package com.nageoffer.ai.rag.eval.importer;

/**
 * LiveRAG 导入结果统计。
 */
public record LiveRagImportResult(
        /** 数据集 ID（复用的或新建的） */
        long datasetId,
        /** 本次抽样题数 */
        int sampleSize,
        /** 成功导入评测条目数 */
        int imported,
        /** 重复（item_key 已存在）跳过数 */
        int skipped,
        /** 入库支持文档数（task_id 写入 expected_doc_ids） */
        int docsIngested,
        /** 入库失败的支持文档数 */
        int docsFailed,
        /** 因文档全部入库失败而被跳过的题数 */
        int itemsSkipped,
        /** 语料归入的文档集合 ID */
        long collectionId,
        /** 语料归入的文档集合名（自动建时与数据集同名） */
        String collectionName,
        /** 总耗时 ms */
        long elapsedMs
) {
}
