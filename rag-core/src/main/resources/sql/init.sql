-- ============================================================
-- springai-rag 业务表初始化
-- 说明：
--   1. spring_ai_store_vector（向量表）由 init.sql 建表 + IndexerNode JDBC 写入
--   2. SPRING_AI_CHAT_MEMORY（会话记忆表）由 JdbcChatMemoryRepository 自动创建
--   3. 业务表加 sa_ 前缀，避免与同库的 15_ragent 表（t_document / t_ingestion_task）结构冲突
-- ============================================================

-- pgvector 扩展（幂等执行）
CREATE EXTENSION IF NOT EXISTS vector;

-- ===== 向量存储表（直接 JDBC 写入，独立控制 content 与 embedding 两列）=====
CREATE TABLE IF NOT EXISTS spring_ai_store_vector (
    id          UUID          PRIMARY KEY,
    content     TEXT          NOT NULL,
    metadata    JSONB         DEFAULT '{}',
    embedding   VECTOR(1024)  NOT NULL
);
-- HNSW 索引（如数据量大可提升检索速度；空表时仅注册，无实际索引结构）
CREATE INDEX IF NOT EXISTS idx_spr_ai_store_vec_emb
    ON spring_ai_store_vector
    USING hnsw (embedding vector_cosine_ops);

-- 文档元数据
CREATE TABLE IF NOT EXISTS sa_document (
    id              BIGSERIAL    PRIMARY KEY,
    doc_id          VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(128),
    source_type     VARCHAR(32),
    source_location VARCHAR(512),
    chunk_count     INT          NOT NULL DEFAULT 0,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING / PROCESSING / DONE / FAILED
    error_msg       TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_document_status ON sa_document (status);

-- 入库任务（异步入库用，阶段 3）
CREATE TABLE IF NOT EXISTS sa_ingestion_task (
    id          BIGSERIAL    PRIMARY KEY,
    task_id     VARCHAR(64)  NOT NULL UNIQUE,
    doc_id      VARCHAR(64)  NOT NULL,
    status      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    progress    INT          NOT NULL DEFAULT 0,
    error_msg   TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_task_doc    ON sa_ingestion_task (doc_id);
CREATE INDEX IF NOT EXISTS idx_sa_task_status ON sa_ingestion_task (status);

-- ===== 入库流水线（P2：DB 驱动 pipeline）=====

-- 流水线模板
CREATE TABLE IF NOT EXISTS sa_ingestion_pipeline (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(128) NOT NULL UNIQUE,
    description  VARCHAR(512),
    created_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    create_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted      SMALLINT     NOT NULL DEFAULT 0
);

-- 流水线节点（settings_json / condition_json 用 PG jsonb）
CREATE TABLE IF NOT EXISTS sa_ingestion_pipeline_node (
    id             BIGSERIAL    PRIMARY KEY,
    pipeline_id    BIGINT       NOT NULL,
    node_id        VARCHAR(64)  NOT NULL,
    node_type      VARCHAR(32)  NOT NULL,          -- fetcher/parser/chunker/enhancer/enricher/indexer
    next_node_id   VARCHAR(64),
    settings_json  JSONB,
    condition_json JSONB,
    created_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    updated_by     VARCHAR(64)  NOT NULL DEFAULT 'system',
    create_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted        SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_sa_pipeline_node UNIQUE (pipeline_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_sa_pipeline_node_pid ON sa_ingestion_pipeline_node (pipeline_id);

-- ===== 对话会话（前端对话历史持久化）=====
CREATE TABLE IF NOT EXISTS sa_conversation (
    id              BIGSERIAL    PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL UNIQUE,
    title           VARCHAR(255) NOT NULL DEFAULT '新对话',
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS sa_message (
    id              BIGSERIAL    PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,   -- user / assistant
    content         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_msg_conv ON sa_message (conversation_id);

-- ===== 节点级执行日志（P7）=====
CREATE TABLE IF NOT EXISTS sa_ingestion_task_node (
    id             BIGSERIAL    PRIMARY KEY,
    task_id        VARCHAR(64)  NOT NULL,
    pipeline_id    BIGINT,
    node_id        VARCHAR(64)  NOT NULL,
    node_type      VARCHAR(32)  NOT NULL,
    node_order     INT          NOT NULL DEFAULT 0,
    status         VARCHAR(32)  NOT NULL,
    duration_ms    BIGINT,
    message        TEXT,
    error_message  TEXT,
    output_json    JSONB,
    create_time    TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_task_node_tid ON sa_ingestion_task_node (task_id);

-- ===== 评测体系（Phase 1：黄金集 + 检索指标）=====

-- 评测数据集
CREATE TABLE IF NOT EXISTS sa_eval_dataset (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(128) NOT NULL UNIQUE,
    description  TEXT,
    item_count   INT          NOT NULL DEFAULT 0,
    deleted      SMALLINT     NOT NULL DEFAULT 0,
    create_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 评测条目（黄金问答；query_embedding 维度同 text-embedding-v3 = 1024）
CREATE TABLE IF NOT EXISTS sa_eval_item (
    id               BIGSERIAL PRIMARY KEY,
    dataset_id       BIGINT    NOT NULL,
    item_key         VARCHAR(64),                            -- 可选用例标识
    category         VARCHAR(32),                            -- qa / summarization / adversarial
    question         TEXT      NOT NULL,
    query_embedding  VECTOR(1024),                           -- 预存，跑批前回填（MyBatis 不映射，走 JDBC）
    expected_doc_ids JSONB,                                  -- 检索 ground truth（task_id 列表）
    expected_answer  TEXT,                                   -- Phase 2 用，本轮留字段
    must_contain     JSONB,                                  -- Phase 2 用
    rubric           TEXT,                                   -- Phase 2 用
    source           VARCHAR(16) NOT NULL DEFAULT 'builtin', -- builtin / feedback
    enabled          SMALLINT   NOT NULL DEFAULT 1,
    create_time      TIMESTAMP  NOT NULL DEFAULT NOW(),
    update_time      TIMESTAMP  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_eval_item_did ON sa_eval_item (dataset_id);

-- 评测运行（一次跑批）
CREATE TABLE IF NOT EXISTS sa_eval_run (
    id                BIGSERIAL PRIMARY KEY,
    dataset_id        BIGINT    NOT NULL,
    status            VARCHAR(32) NOT NULL,                  -- PENDING / RUNNING / DONE / FAILED
    total             INT,
    done              INT       NOT NULL DEFAULT 0,
    param_snapshot    JSONB,                                 -- 本次实际参数（topK/threshold/...）
    aggregate_metrics JSONB,                                 -- {recall_at_5:{mean,median}, ...}
    started_at        TIMESTAMP,
    finished_at       TIMESTAMP,
    create_time       TIMESTAMP NOT NULL DEFAULT NOW(),
    update_time       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_eval_run_did ON sa_eval_run (dataset_id);

-- 评测指标（每条每指标）
CREATE TABLE IF NOT EXISTS sa_eval_metric (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT   NOT NULL,
    item_id             BIGINT   NOT NULL,
    question            TEXT,                                -- 冗余便于查询
    retrieved_doc_ids   JSONB,                               -- 实际召回（去重后）
    retrieved_doc_names JSONB,                               -- 人类可读，展示用
    metric_name         VARCHAR(32) NOT NULL,                -- recall_at_5 / precision_at_5 / mrr / ndcg
    score               NUMERIC(6,4) NOT NULL,
    detail              JSONB,                               -- {k, hitCount, expectedCount, ...}
    latency_ms          BIGINT,
    trace_id            VARCHAR(64),                         -- 预留，关联 OpenObserve
    create_time         TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_eval_metric_run ON sa_eval_metric (run_id);
-- 单条重评（保留历史）：评估序号 0=原始批 1..=重评次数；备注；该条是否启用查询改写
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS attempt INT NOT NULL DEFAULT 0;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS rewrite BOOLEAN NOT NULL DEFAULT false;
-- 期望召回文档（与 retrieved_* 对称冗余落库；黄金集 sa_eval_item.expected_doc_ids 在运行记录上的投影，
-- 即便检索失败也写入，便于前端「期望 vs 实际」对照）
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS expected_doc_ids JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS expected_doc_names JSONB;
-- agent 框架对照：执行轨迹 + 范式轴
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS agent_trace JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32) NOT NULL DEFAULT 'naive';
ALTER TABLE sa_eval_run    ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32) NOT NULL DEFAULT 'naive';
-- 条目分类（冗余自 sa_eval_item.category，便于运行记录按分类展示/筛选）
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS category VARCHAR(32);

-- ===== 评测体系中文释义（表/列注释，DB 客户端可见）=====
COMMENT ON TABLE sa_eval_dataset IS '评测数据集';
COMMENT ON COLUMN sa_eval_dataset.id IS '自增主键';
COMMENT ON COLUMN sa_eval_dataset.name IS '数据集名称（全局唯一）';
COMMENT ON COLUMN sa_eval_dataset.description IS '数据集描述';
COMMENT ON COLUMN sa_eval_dataset.item_count IS '条目数（冗余，便于列表展示）';
COMMENT ON COLUMN sa_eval_dataset.deleted IS '逻辑删除：0-正常 1-已删除';
COMMENT ON COLUMN sa_eval_dataset.create_time IS '创建时间';
COMMENT ON COLUMN sa_eval_dataset.update_time IS '更新时间';

COMMENT ON TABLE sa_eval_item IS '评测条目（黄金问答）';
COMMENT ON COLUMN sa_eval_item.id IS '自增主键';
COMMENT ON COLUMN sa_eval_item.dataset_id IS '所属数据集 ID';
COMMENT ON COLUMN sa_eval_item.item_key IS '可选用例标识';
COMMENT ON COLUMN sa_eval_item.category IS '分类：qa / summarization / adversarial';
COMMENT ON COLUMN sa_eval_item.question IS '用户问题';
COMMENT ON COLUMN sa_eval_item.query_embedding IS '问题向量（pgvector 1024维，跑批前回填；MyBatis 不映射，走 JDBC）';
COMMENT ON COLUMN sa_eval_item.expected_doc_ids IS '检索 ground truth：期望命中的 task_id 列表（jsonb 数组，与 chunk.metadata.doc_id 同源）';
COMMENT ON COLUMN sa_eval_item.expected_answer IS '标准答案（Phase 2 生成质量指标用）';
COMMENT ON COLUMN sa_eval_item.must_contain IS '答案必含关键词（Phase 2 用，jsonb）';
COMMENT ON COLUMN sa_eval_item.rubric IS '开放式评分标准（Phase 2 用）';
COMMENT ON COLUMN sa_eval_item.source IS '来源：builtin(人工构造) / feedback(badcase 回流)';
COMMENT ON COLUMN sa_eval_item.enabled IS '是否启用：1-启用 0-禁用';
COMMENT ON COLUMN sa_eval_item.create_time IS '创建时间';
COMMENT ON COLUMN sa_eval_item.update_time IS '更新时间';

COMMENT ON TABLE sa_eval_run IS '评测运行（一次跑批）';
COMMENT ON COLUMN sa_eval_run.id IS '自增主键';
COMMENT ON COLUMN sa_eval_run.dataset_id IS '数据集 ID';
COMMENT ON COLUMN sa_eval_run.status IS '运行状态：PENDING / RUNNING / DONE / FAILED';
COMMENT ON COLUMN sa_eval_run.total IS '条目总数';
COMMENT ON COLUMN sa_eval_run.done IS '已完成条目数';
COMMENT ON COLUMN sa_eval_run.param_snapshot IS '本次实际参数快照（topK/threshold/recallBudget/candidateLimit/contextTopK，jsonb）';
COMMENT ON COLUMN sa_eval_run.aggregate_metrics IS '聚合指标（各指标 mean/median/min/max，jsonb）';
COMMENT ON COLUMN sa_eval_run.started_at IS '开始时间';
COMMENT ON COLUMN sa_eval_run.finished_at IS '完成时间';
COMMENT ON COLUMN sa_eval_run.create_time IS '创建时间';
COMMENT ON COLUMN sa_eval_run.update_time IS '更新时间';

COMMENT ON TABLE sa_eval_metric IS '评测指标（每条条目 × 每个指标）';
COMMENT ON COLUMN sa_eval_metric.id IS '自增主键';
COMMENT ON COLUMN sa_eval_metric.run_id IS '所属运行 ID';
COMMENT ON COLUMN sa_eval_metric.item_id IS '所属条目 ID';
COMMENT ON COLUMN sa_eval_metric.question IS '条目问题（冗余，便于查询展示）';
COMMENT ON COLUMN sa_eval_metric.retrieved_doc_ids IS '实际召回的 doc_id 列表（文档级去重后，jsonb）';
COMMENT ON COLUMN sa_eval_metric.retrieved_doc_names IS '实际召回的 doc_name 列表（人类可读，展示用，jsonb）';
COMMENT ON COLUMN sa_eval_metric.expected_doc_ids IS '期望召回的 doc_id 列表（黄金集 sa_eval_item.expected_doc_ids 冗余投影，jsonb）';
COMMENT ON COLUMN sa_eval_metric.expected_doc_names IS '期望召回的 doc_name 列表（人类可读，展示用，jsonb）';
COMMENT ON COLUMN sa_eval_metric.metric_name IS '指标名：recall_at_5 / precision_at_5 / mrr / ndcg 等';
COMMENT ON COLUMN sa_eval_metric.score IS '指标得分（0~1；-1 表示该条执行失败）';
COMMENT ON COLUMN sa_eval_metric.detail IS '指标明细（k / hitCount / expectedCount 等，jsonb）';
COMMENT ON COLUMN sa_eval_metric.latency_ms IS '该条检索耗时（毫秒）';
COMMENT ON COLUMN sa_eval_metric.trace_id IS 'traceId（预留，关联 OpenObserve）';
COMMENT ON COLUMN sa_eval_metric.category IS '条目分类（冗余自 sa_eval_item.category，便于运行记录展示/筛选）';
COMMENT ON COLUMN sa_eval_metric.create_time IS '创建时间';

-- ===== Agent 执行轨迹（线上 chat 持久化，供管理后台分析多步决策规律、反哺 naive）=====
CREATE TABLE IF NOT EXISTS sa_agent_trace (
    id               BIGSERIAL    PRIMARY KEY,
    conversation_id  VARCHAR(64),                   -- 所属会话（关联 sa_conversation.conversation_id）
    message_id       BIGINT,                        -- 关联的 assistant 消息 id（sa_message.id），可空
    paradigm         VARCHAR(32) NOT NULL,          -- 范式：naive/crag/self_rag/react/plan_execute
    question         TEXT,                          -- 用户原始问题
    steps            JSONB,                         -- AgentStep 数组（action/thought/input/output/latency）
    llm_call_count   INT,                           -- 编排内 LLM 调用次数（不含最终回答）
    total_latency_ms BIGINT,                        -- 检索编排总耗时
    create_time      TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_agent_trace_conv ON sa_agent_trace (conversation_id);
CREATE INDEX IF NOT EXISTS idx_sa_agent_trace_para ON sa_agent_trace (paradigm);
COMMENT ON TABLE  sa_agent_trace IS 'Agent 执行轨迹（线上 chat 落库，分析多步决策反哺 naive）';
COMMENT ON COLUMN sa_agent_trace.steps IS 'AgentStep 数组（action/thought/inputSummary/outputSummary/latencyMs），jsonb';

-- sa_agent_trace 演进：trace_id（OTel traceId，对话页跳 OpenObserve 全链路）+ message_id 索引（按消息查轨迹）
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_sa_agent_trace_msg ON sa_agent_trace (message_id);
COMMENT ON COLUMN sa_agent_trace.trace_id IS '本次请求的 OpenTelemetry traceId（32 位 hex，跳 OpenObserve 全链路），可空';

-- ===== 文档集合（文件集）=====

CREATE TABLE IF NOT EXISTS sa_doc_collection (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512),
    create_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
-- 名称唯一（仅未逻辑删的行）
CREATE UNIQUE INDEX IF NOT EXISTS uk_sa_doc_collection_name
    ON sa_doc_collection (lower(name)) WHERE deleted = 0;

-- sa_document 加归属集合列（NULL = 独立文件，未归入任何集合）
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS collection_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_sa_document_collection ON sa_document (collection_id);

COMMENT ON TABLE sa_doc_collection IS '文档集合（文件集）：用户手动建集合，文档归入集合';
COMMENT ON COLUMN sa_doc_collection.id IS '自增主键';
COMMENT ON COLUMN sa_doc_collection.name IS '集合名称（未逻辑删时唯一）';
COMMENT ON COLUMN sa_doc_collection.description IS '集合描述';
COMMENT ON COLUMN sa_doc_collection.deleted IS '逻辑删除：0-正常 1-已删除';
COMMENT ON COLUMN sa_document.collection_id IS '所属集合 ID（NULL=独立文件）';
