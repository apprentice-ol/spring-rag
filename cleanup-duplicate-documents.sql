-- ============================================================
-- LiveRAG 重复文档清理（消除「同内容多身份」的孪生副本）
--
-- 背景：LiveRagImporter 丢弃了源数据的 urn doc_id（LiveRagImporter.java:196 用
--   「题号-序号」重新命名，:319 注释明说"只取 content"），导致同一篇源文档被多题
--   引用时被重复入库成多份。库内实测 1036 篇文档 / 974 份唯一内容 / 62 个冗余副本，
--   与源数据 "1032 次引用 - 970 篇唯一文档 = 62" 完全吻合。
--
--   后果：expected_doc_ids 指向其中一份副本，检索按内容召回可能捞到另一份 →
--   doc_id 对不上 → 该题恒记 0 分（run82 的 62 条召回失败里有 38 条属此类）。
--
-- 本脚本做什么：
--   1. 按「全文内容签名」找出整篇重复的文档组
--   2. 每组保留 sa_document.id 最小的一份（最早导入，确定性）
--   3. 把 sa_eval_item.expected_doc_ids 中被删副本的引用重指向保留的那份
--   4. 删除冗余副本的向量与 sa_document 行
--
-- 本脚本不做什么（刻意）：
--   - 不动 sa_eval_metric 的任何列。该表是历史记录，其 recall/ndcg 分数是按当时的
--     ground truth 算出来的；重指向会造出"分数说 0 分、期望文档却对得上"的矛盾记录。
--     历史 run 的 expected_doc_ids 会留下悬空引用，这是历史事实，不做修饰。
--   - 不动 retrieved_doc_ids / retrieved_doc_names（同上，历史事实）。
--
-- 用法（务必先预演）：
--   预演（默认，最后 ROLLBACK，不改动任何数据）：
--     docker exec -i rag-postgres psql -U postgres -d springai_rag \
--       -v apply=0 < cleanup-duplicate-documents.sql
--   实际执行：
--     docker exec -i rag-postgres psql -U postgres -d springai_rag \
--       -v apply=1 < cleanup-duplicate-documents.sql
--
--   幂等：清理干净后再跑，重复组为 0，脚本空转。
-- ============================================================

\if :{?apply}
\else
\set apply 0
\endif

\pset border 2
\set ON_ERROR_STOP on

BEGIN;

-- ------------------------------------------------------------
-- 1. 内容签名：同一组 chunk 全文哈希即视为同一篇文档
-- ------------------------------------------------------------
-- 只统计「向量库里真有 chunk」的文档——没有向量的文档不参与检索，不在清理范围。
DROP TABLE IF EXISTS tmp_doc_sig;
CREATE TEMP TABLE tmp_doc_sig AS
SELECT v.metadata->>'doc_id'   AS doc_id,
       v.metadata->>'doc_name' AS doc_name,
       md5(string_agg(md5(v.content), ',' ORDER BY md5(v.content))) AS sig,
       count(*)::int           AS chunks
FROM spring_ai_store_vector v
WHERE v.metadata->>'doc_id' IS NOT NULL
  AND v.metadata->>'doc_id' <> ''
GROUP BY 1, 2;

-- ------------------------------------------------------------
-- 2. 重复组 → 删除映射（幸存者 = sa_document.id 最小者）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS tmp_dup_map;
CREATE TEMP TABLE tmp_dup_map AS
WITH joined AS (
    SELECT s.doc_id, s.doc_name, s.sig, s.chunks, d.id AS doc_pk
    FROM tmp_doc_sig s
    JOIN sa_document d ON d.doc_id = s.doc_id
),
survivor AS (
    SELECT sig,
           (array_agg(doc_id   ORDER BY doc_pk))[1] AS survivor_id,
           (array_agg(doc_name ORDER BY doc_pk))[1] AS survivor_name,
           count(*)                                AS group_size
    FROM joined
    GROUP BY sig
    HAVING count(*) > 1
)
SELECT j.doc_id   AS deleted_doc_id,
       j.doc_name AS deleted_doc_name,
       j.chunks   AS deleted_chunks,
       v.survivor_id,
       v.survivor_name,
       v.group_size
FROM joined j
JOIN survivor v ON v.sig = j.sig
WHERE j.doc_id <> v.survivor_id;

\echo ''
\echo '=== 1. 将要清理的内容（预演结果）==='
SELECT count(DISTINCT survivor_id)       AS 重复文档组数,
       count(DISTINCT survivor_id)       AS 每组保留1篇,
       count(*)                          AS 删除文档数,
       sum(deleted_chunks)               AS 删除向量数,
       max(group_size)                   AS 最大组内文档数
FROM tmp_dup_map;

\echo ''
\echo '=== 2. 将被重指向的评测题数（sa_eval_item）==='
SELECT count(*) AS 受影响题数
FROM sa_eval_item i
WHERE i.expected_doc_ids IS NOT NULL
  AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(i.expected_doc_ids) e(val)
              JOIN tmp_dup_map m ON m.deleted_doc_id = e.val);

\echo ''
\echo '=== 3. 抽样：前 10 组（删 → 留）==='
SELECT deleted_doc_name AS 删除, survivor_name AS 保留, group_size AS 组内份数
FROM tmp_dup_map ORDER BY deleted_doc_name LIMIT 10;

\echo ''
\echo '=== 4. 安全校验：被删文档是否仍被别的东西引用 ==='
SELECT (SELECT count(*) FROM sa_ingestion_task t
        WHERE t.doc_id IN (SELECT deleted_doc_id FROM tmp_dup_map)) AS 被入库任务引用,
       (SELECT count(*) FROM tmp_dup_map)                           AS 待删文档数;

-- ------------------------------------------------------------
-- 3. 备份（可回滚）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS bak_dup_cleanup_map;
CREATE TABLE bak_dup_cleanup_map AS SELECT * FROM tmp_dup_map;

DROP TABLE IF EXISTS bak_dup_cleanup_eval_item;
CREATE TABLE bak_dup_cleanup_eval_item AS
SELECT i.id, i.expected_doc_ids
FROM sa_eval_item i
WHERE i.expected_doc_ids IS NOT NULL
  AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(i.expected_doc_ids) e(val)
              JOIN tmp_dup_map m ON m.deleted_doc_id = e.val);

DROP TABLE IF EXISTS bak_dup_cleanup_vector;
CREATE TABLE bak_dup_cleanup_vector AS
SELECT v.* FROM spring_ai_store_vector v
WHERE v.metadata->>'doc_id' IN (SELECT deleted_doc_id FROM tmp_dup_map);

DROP TABLE IF EXISTS bak_dup_cleanup_document;
CREATE TABLE bak_dup_cleanup_document AS
SELECT d.* FROM sa_document d
WHERE d.doc_id IN (SELECT deleted_doc_id FROM tmp_dup_map);

\echo ''
\echo '=== 5. 已备份到 bak_dup_cleanup_{map,eval_item,vector,document} ==='

-- ------------------------------------------------------------
-- 4. 重指向 sa_eval_item.expected_doc_ids
-- ------------------------------------------------------------
-- DISTINCT：两道题各指向组内不同副本时，重指向后会收敛成同一个 id，需去重。
UPDATE sa_eval_item i
SET expected_doc_ids = (
        SELECT jsonb_agg(DISTINCT to_jsonb(COALESCE(m.survivor_id, e.val)))
        FROM jsonb_array_elements_text(i.expected_doc_ids) e(val)
        LEFT JOIN tmp_dup_map m ON m.deleted_doc_id = e.val
    ),
    update_time = now()
WHERE i.expected_doc_ids IS NOT NULL
  AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(i.expected_doc_ids) e(val)
              JOIN tmp_dup_map m ON m.deleted_doc_id = e.val);

-- ------------------------------------------------------------
-- 5. 删除冗余向量与文档
-- ------------------------------------------------------------
DELETE FROM spring_ai_store_vector v
WHERE v.metadata->>'doc_id' IN (SELECT deleted_doc_id FROM tmp_dup_map);

DELETE FROM sa_document d
WHERE d.doc_id IN (SELECT deleted_doc_id FROM tmp_dup_map);

-- ------------------------------------------------------------
-- 6. 验证
-- ------------------------------------------------------------
\echo ''
\echo '=== 6. 清理后核对 ==='
WITH sig AS (
    SELECT v.metadata->>'doc_name' AS nm,
           md5(string_agg(md5(v.content), ',' ORDER BY md5(v.content))) AS s
    FROM spring_ai_store_vector v
    WHERE v.metadata->>'doc_name' IS NOT NULL
    GROUP BY 1),
g AS (SELECT s, count(*) AS docs FROM sig GROUP BY s)
SELECT (SELECT count(*) FROM sig)                      AS 文档数,
       (SELECT count(*) FROM g)                        AS 唯一内容数,
       (SELECT coalesce(sum(docs - 1), 0) FROM g)      AS 剩余冗余副本;

\echo ''
\echo '=== 7. 悬空的期望引用（应为 0）==='
SELECT count(*) AS 引用了不存在文档的题数
FROM sa_eval_item i
CROSS JOIN LATERAL jsonb_array_elements_text(i.expected_doc_ids) e(val)
WHERE NOT EXISTS (SELECT 1 FROM sa_document d WHERE d.doc_id = e.val);

-- ------------------------------------------------------------
-- 7. 提交 / 回滚
-- ------------------------------------------------------------
\if :apply
COMMIT;
\echo ''
\echo '>>> apply=1：已提交，清理完成。'
\echo '>>> 备份表 bak_dup_cleanup_* 保留在库中，确认无误后可自行 DROP。'
\else
ROLLBACK;
\echo ''
\echo '>>> apply=0（默认）：预演结束，已回滚，**未改动任何数据**。'
\echo '>>> 确认上面 1-5 节结果符合预期后，加 -v apply=1 重新执行即可真正清理。'
\endif
