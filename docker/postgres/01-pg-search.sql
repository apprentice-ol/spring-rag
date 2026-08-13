-- springai-rag 首次初始化：建 pg_search 扩展（幂等）
-- pg_search 依赖 vector（pgvector 镜像自带扩展文件但未自动建），须先建 vector。
-- BM25 索引由应用启动时自动创建（Bm25KeywordSearchChannel.verifyPrerequisites），
-- 因为 spring_ai_store_vector 表是应用启动后才建的。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;
