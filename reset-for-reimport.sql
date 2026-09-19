-- ============================================================
-- 清空语料与评测数据，为重新导入做准备
--
-- 为什么清：
--   旧导入器用「题号-序号」给文档另起身份（丢弃源 urn），导致
--     · 62 个冗余副本（同一篇文章被多题引用却入库多份）
--     · 评测按 doc_id 打分，指到副本上就 0 分（38 条"召回了内容却记 0 分"）
--     · 文档名冒充某道题的专属文档（题 867 的文档叫 687-doc2）
--   新导入器把源 urn 直接当 doc_id，并改名 LiveRAG-{urn8}-{题号d序号}…，
--   旧数据没有保留价值，全量重来最干净。
--
-- ⚠️ 执行前必须先做完：
--   1. 导出评测历史（已做：docs/snapshots/eval-history-20260918.csv，79 次 run）
--   2. 新导入器已编译并重启（否则清完就没有导入器可用）
--
-- 清什么：
--   spring_ai_store_vector   全部向量
--   sa_document              全部文档登记
--   sa_ingestion_task(_node) 入库流水
--   sa_eval_metric           评测明细（引用已失效的 doc_id）
--   sa_eval_run              评测运行（聚合分数已导出）
--   sa_eval_item             评测条目（expected_doc_ids 会随重导重建）
--   sa_cache_answer          答案缓存（docver 指向旧文档）
--
-- 不清什么（保留）：
--   sa_eval_dataset          数据集本身（导入器按名复用；条目清空后重导不会跳过）
--   sa_doc_collection        集合（导入器按名复用）
--   sa_conversation / sa_message / spring_ai_chat_memory   对话历史，与语料无关
--   sa_prompt*               提示词资产
--
-- 用法（务必先预演）：
--   预演: docker exec -i rag-postgres psql -U postgres -d springai_rag -v apply=0 < reset-for-reimport.sql
--   执行: docker exec -i rag-postgres psql -U postgres -d springai_rag -v apply=1 < reset-for-reimport.sql
-- ============================================================

\if :{?apply}
\else
\set apply 0
\endif

\pset border 2
\set ON_ERROR_STOP on

BEGIN;

\echo ''
\echo '=== 1. 当前存量（执行前）==='
SELECT '向量' AS 项, count(*) AS 行数 FROM spring_ai_store_vector
UNION ALL SELECT '文档', count(*) FROM sa_document
UNION ALL SELECT '入库节点', count(*) FROM sa_ingestion_task_node
UNION ALL SELECT '评测条目', count(*) FROM sa_eval_item
UNION ALL SELECT '评测运行', count(*) FROM sa_eval_run
UNION ALL SELECT '评测明细', count(*) FROM sa_eval_metric
UNION ALL SELECT '答案缓存', count(*) FROM sa_cache_answer;

-- TRUNCATE 在 PG 里是事务性的，预演模式下随 ROLLBACK 一起撤销
TRUNCATE spring_ai_store_vector;
TRUNCATE sa_document;
TRUNCATE sa_ingestion_task_node;
TRUNCATE sa_ingestion_task;
TRUNCATE sa_eval_metric;
TRUNCATE sa_eval_run;
TRUNCATE sa_eval_item;
TRUNCATE sa_cache_answer;

-- 数据集条数归零（数据集本身保留，导入器按名复用）
UPDATE sa_eval_dataset SET item_count = 0;

\echo ''
\echo '=== 2. 清空后核对（都应为 0）==='
SELECT '向量' AS 项, count(*) AS 行数 FROM spring_ai_store_vector
UNION ALL SELECT '文档', count(*) FROM sa_document
UNION ALL SELECT '评测条目', count(*) FROM sa_eval_item
UNION ALL SELECT '评测运行', count(*) FROM sa_eval_run
UNION ALL SELECT '评测明细', count(*) FROM sa_eval_metric;

\echo ''
\echo '=== 3. 保留的东西 ==='
SELECT '数据集' AS 项, count(*) AS 行数 FROM sa_eval_dataset
UNION ALL SELECT '集合', count(*) FROM sa_doc_collection
UNION ALL SELECT '对话', count(*) FROM sa_conversation;

\if :apply
COMMIT;
\echo ''
\echo '>>> apply=1：已清空。现在可以重新导入语料（导入器会自动重建数据集条目）。'
\else
ROLLBACK;
\echo ''
\echo '>>> apply=0（默认）：预演结束，已回滚，**未改动任何数据**。'
\endif
