-- ============================================================
-- sa_eval_item 增加难度分级字段
--
-- 背景：LiveRAG 源数据每题带 IRT 标注，官方按 irt_diff 四分位分 4 档
-- （E / M / D / HD，每档约 224 题）。原先导入器只取了 question/answer/category，
-- 这几个字段丢了，评测无法按难度切片。
--
-- 字段语义：
--   difficulty  E / M / D / HD —— 由 irt_diff 的四分位定档
--   irt_diff    IRT 难度参数 b —— **越大越难**
--               （官方资料对方向有矛盾说法，本项目用数据自证：
--                irt_diff 四分位 vs 平均 ACS 为 1.428 / 1.039 / 0.713 / 0.202，
--                皮尔逊相关 -0.970 —— irt_diff 越高 ACS 越低 = 越难）
--   irt_disc    IRT 区分度参数 a
--   acs         各参赛系统平均正确率（越大越容易）
--   acs_std     ACS 标准差
--
-- 用法：
--   docker exec -i rag-postgres psql -U postgres -d springai_rag < migrate-add-difficulty.sql
--
-- 幂等：ADD COLUMN IF NOT EXISTS，重复执行安全。
-- ============================================================

\set ON_ERROR_STOP on
\pset border 2

BEGIN;

ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS difficulty VARCHAR(8);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS irt_diff   NUMERIC(8,4);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS irt_disc   NUMERIC(8,4);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS acs        NUMERIC(8,4);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS acs_std    NUMERIC(8,4);

COMMENT ON COLUMN sa_eval_item.difficulty IS '难度档：E / M / D / HD（由 irt_diff 四分位定档）';
COMMENT ON COLUMN sa_eval_item.irt_diff   IS 'IRT 难度参数 b，越大越难（与 ACS 相关 -0.97）';
COMMENT ON COLUMN sa_eval_item.irt_disc   IS 'IRT 区分度参数 a';
COMMENT ON COLUMN sa_eval_item.acs        IS '各参赛系统平均正确率，越大越容易';
COMMENT ON COLUMN sa_eval_item.acs_std    IS 'ACS 标准差';

\echo ''
\echo '=== 迁移后的列 ==='
SELECT column_name AS 列名, data_type AS 类型
FROM information_schema.columns
WHERE table_name = 'sa_eval_item'
  AND column_name IN ('difficulty','irt_diff','irt_disc','acs','acs_std')
ORDER BY column_name;

\echo ''
\echo '=== 按难度分布（重新导入后才有值）==='
SELECT coalesce(difficulty, '(未填)') AS 难度, count(*) AS 题数
FROM sa_eval_item GROUP BY 1 ORDER BY 1;

COMMIT;
\echo ''
\echo '>>> 迁移完成。'
