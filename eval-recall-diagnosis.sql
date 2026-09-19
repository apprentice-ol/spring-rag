-- ============================================================
-- 评测召回诊断（只读，不改任何数据）
--
-- 在服务器上执行：
--   docker exec -i rag-postgres psql -U postgres -d springai_rag \
--     -v run_id=0 < eval-recall-diagnosis.sql
--
--   run_id=0  → 自动取最近一次 DONE 的运行
--   run_id=81 → 指定某次运行
--
-- 依赖表：sa_eval_run / sa_eval_metric / sa_eval_item / sa_document
-- ============================================================

\if :{?run_id}
\else
\set run_id 0
\endif
\pset border 2
\set QUIET on
\set QUIET off

\echo ''
\echo '=== 0. 本次诊断的 run ==='
WITH r AS (
    SELECT CASE WHEN :run_id = 0
                THEN (SELECT max(id) FROM sa_eval_run WHERE status = 'DONE')
                ELSE :run_id END AS id)
SELECT e.id, e.status, e.total, e.done,
       to_char(e.create_time, 'YYYY-MM-DD HH24:MI') AS created,
       e.param_snapshot ->> 'topK'        AS topk,
       e.param_snapshot ->> 'contextTopK' AS ctx_topk,
       e.param_snapshot ->> 'rewrite'     AS rewrite,
       e.aggregate_metrics -> 'recall_at_5' ->> 'mean' AS recall5
FROM sa_eval_run e JOIN r ON e.id = r.id;

\echo ''
\echo '=== 1. 各指标分数分布（零分=彻底没召回；0<score<1=只召回了一部分）==='
WITH r AS (SELECT CASE WHEN :run_id = 0
                       THEN (SELECT max(id) FROM sa_eval_run WHERE status = 'DONE')
                       ELSE :run_id END AS id)
SELECT m.metric_name,
       count(*)                                        AS 用例数,
       count(*) FILTER (WHERE m.score = 0)             AS 零分,
       count(*) FILTER (WHERE m.score > 0 AND m.score < 1) AS 部分,
       count(*) FILTER (WHERE m.score = 1)             AS 满分
FROM sa_eval_metric m JOIN r ON m.run_id = r.id
GROUP BY m.metric_name ORDER BY m.metric_name;

\echo ''
\echo '=== 2. 召回文档数 × 期望文档数（核心：10 个 chunk 到底覆盖了几篇文档）==='
WITH r AS (SELECT CASE WHEN :run_id = 0
                       THEN (SELECT max(id) FROM sa_eval_run WHERE status = 'DONE')
                       ELSE :run_id END AS id)
SELECT jsonb_array_length(COALESCE(m.retrieved_doc_ids, '[]'::jsonb)) AS 召回doc数,
       jsonb_array_length(COALESCE(i.expected_doc_ids, '[]'::jsonb))  AS 期望doc数,
       count(*)                     AS 用例数,
       round(avg(m.score), 3)       AS 平均召回率
FROM sa_eval_metric m
JOIN sa_eval_item i ON i.id = m.item_id
JOIN r ON m.run_id = r.id
WHERE m.metric_name = 'recall_at_5'
GROUP BY 1, 2 ORDER BY 1, 2;

\echo ''
\echo '=== 3. 召回失败清单（score<1，含期望文档名与实际召回）==='
WITH r AS (SELECT CASE WHEN :run_id = 0
                       THEN (SELECT max(id) FROM sa_eval_run WHERE status = 'DONE')
                       ELSE :run_id END AS id)
SELECT m.item_id,
       i.category,
       left(replace(m.question, E'\n', ' '), 52) AS question,
       m.score,
       m.detail ->> 'hitCount'      AS 命中,
       m.detail ->> 'expectedCount' AS 期望,
       (SELECT string_agg(d.name, ', ') FROM sa_document d
         WHERE d.doc_id IN (SELECT jsonb_array_elements_text(i.expected_doc_ids))) AS 期望文档,
       left(m.retrieved_doc_names::text, 90) AS 实际召回
FROM sa_eval_metric m
JOIN sa_eval_item i ON i.id = m.item_id
JOIN r ON m.run_id = r.id
WHERE m.metric_name = 'recall_at_5' AND m.score < 1
ORDER BY m.score, m.item_id;

\echo ''
\echo '=== 4. 跨 run 稳定复发的"老赖"问题（系统性缺陷，不是随机波动）==='
SELECT m.item_id,
       left(replace(i.question, E'\n', ' '), 46) AS question,
       count(DISTINCT m.run_id)                       AS 出现在run数,
       count(*) FILTER (WHERE m.score = 0)            AS 全失败次数,
       count(*) FILTER (WHERE m.score > 0 AND m.score < 1) AS 部分失败次数
FROM sa_eval_metric m
JOIN sa_eval_item i ON i.id = m.item_id
WHERE m.metric_name = 'recall_at_5'
GROUP BY m.item_id, i.question
HAVING count(*) FILTER (WHERE m.score < 1) >= 2
ORDER BY 全失败次数 DESC, 部分失败次数 DESC;

\echo ''
\echo '=== 5. 期望文档是否真的在库里（0 = 数据缺失，非检索问题）==='
WITH r AS (SELECT CASE WHEN :run_id = 0
                       THEN (SELECT max(id) FROM sa_eval_run WHERE status = 'DONE')
                       ELSE :run_id END AS id),
z AS (
    SELECT DISTINCT jsonb_array_elements_text(i.expected_doc_ids) AS doc
    FROM sa_eval_metric m
    JOIN sa_eval_item i ON i.id = m.item_id
    JOIN r ON m.run_id = r.id
    WHERE m.metric_name = 'recall_at_5')
SELECT count(*) AS 期望文档总数, count(d.doc_id) AS 库中存在
FROM z LEFT JOIN sa_document d ON d.doc_id = z.doc;

\echo ''
\echo '=== 6. 连续失败问题的「期望文档」是否漏导入 ==='
WITH r AS (SELECT CASE WHEN :run_id = 0
                       THEN (SELECT max(id) FROM sa_eval_run WHERE status = 'DONE')
                       ELSE :run_id END AS id)
SELECT i.id AS item_id, e.doc AS 缺失的期望文档
FROM sa_eval_metric m
JOIN sa_eval_item i ON i.id = m.item_id
JOIN r ON m.run_id = r.id
CROSS JOIN LATERAL jsonb_array_elements_text(i.expected_doc_ids) AS e(doc)
LEFT JOIN sa_document d ON d.doc_id = e.doc
WHERE m.metric_name = 'recall_at_5' AND m.score = 0 AND d.doc_id IS NULL;

\echo ''
\echo '=== 完 ==='
