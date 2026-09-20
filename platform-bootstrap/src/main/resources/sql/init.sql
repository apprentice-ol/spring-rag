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

-- ===== 父块存档（小块检索、大块生成，2026-09-18；见 plan/2026-09-18-small-to-big-retrieval.md）=====
-- 向量表存子块（打分排序粒度），本表存父块原文（生成上下文粒度）。
-- parent_key = doc_id + ":" + 父块 chunk_index（IndexerNode 生成，确定性）；与向量表同生命周期
-- （IngestionEngineService 幂等清理 / IngestionController 删除文档时同批删除）。
CREATE TABLE IF NOT EXISTS sa_chunk_parent (
    id            BIGSERIAL    PRIMARY KEY,
    parent_key    VARCHAR(128) NOT NULL UNIQUE,
    doc_id        VARCHAR(64)  NOT NULL,
    collection_id BIGINT,
    doc_name      VARCHAR(255),
    outline_path  VARCHAR(512),
    content       TEXT         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_chunk_parent_doc ON sa_chunk_parent (doc_id);
-- 子块 → 父块的反查走 parent_key（聚合器批量 IN 查询）
CREATE INDEX IF NOT EXISTS idx_spr_ai_store_vec_parent
    ON spring_ai_store_vector ((metadata->>'parent_key'))
    WHERE metadata->>'parent_key' IS NOT NULL;

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
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    citations       JSONB        NULL        -- RAG 回答的引用溯源（[{ref,docId,docName,chunkCount,preview,sourceLocation}]）
);
CREATE INDEX IF NOT EXISTS idx_sa_msg_conv ON sa_message (conversation_id);
-- 旧库补列（幂等；init 模式 always，每次启动安全执行）
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS citations JSONB;
-- 人在环中：追问/决策卡片的结构化载荷（刷新页面后卡片要能重新渲染，正文文本还原不出结构）
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS clarify JSONB;
COMMENT ON COLUMN sa_message.clarify IS '澄清卡片结构化载荷（ClarifyRequest JSON，刷新后重渲染用）';

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
    category         VARCHAR(32),                            -- 7 类：factoid/definition/explanation/multi-aspect/list/comparison/yes-no
    question         TEXT      NOT NULL,
    -- 难度（LiveRAG 源数据的 IRT 标注；官方按 irt_diff 四分位分 4 档，每档约 224 题）
    difficulty       VARCHAR(8),                             -- E / M / D / HD（由 irt_diff 四分位定档）
    irt_diff         NUMERIC(8,4),                           -- IRT 难度参数 b：**越大越难**（实测与 ACS 相关 -0.97）
    irt_disc         NUMERIC(8,4),                           -- IRT 区分度参数 a
    acs              NUMERIC(8,4),                           -- 各参赛系统平均正确率：越大越容易
    acs_std          NUMERIC(8,4),                           -- ACS 标准差
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
-- ⚠️ agent_trace 自 2026-09-18 起不再写入本表（列保留兼容历史数据）：实测单条轨迹平均 22KB 而每条
-- item 有 9 个指标行，把同一份轨迹存 9 遍，sa_eval_metric 395MB 里 352MB（89%）是这份冗余副本。
-- 轨迹改存 sa_eval_item_trace（每 run+item+attempt 一行），本列不再被任何读路径使用
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS agent_trace JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32) NOT NULL DEFAULT 'naive';
ALTER TABLE sa_eval_run    ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32) NOT NULL DEFAULT 'naive';
-- 条目分类（冗余自 sa_eval_item.category，便于运行记录按分类展示/筛选）
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS category VARCHAR(32);
-- 答案质量评测展示（answerEval）：标准答案 + 系统生成答案
-- （expected_answer 冗余自 sa_eval_item；generated_answer 为评测时 LLM 生成，落库供前端对照）
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS expected_answer TEXT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS generated_answer TEXT;
-- per-question 检索模式标识（仅检索期望文档；重评可覆盖原 run 设置，
-- 上限对照记录须与常规记录区分展示，避免误读分数）
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS per_question BOOLEAN NOT NULL DEFAULT false;

-- 评测条目 agent 执行轨迹（每 run + item + attempt 一行）
-- 从 sa_eval_metric 拆出：轨迹是「每条 item 一份」的，塞进「每条 item × 每个指标」的指标表会被复制
-- 指标数次（实测 9 次），冗余副本占满整表。拆出后落库体积降约 89%，且轨迹与指标解耦，
-- 指标表可以放心按指标聚合/裁剪列。attempt 与 sa_eval_metric 同口径（0=原始批，1..=第 n 次重评）
CREATE TABLE IF NOT EXISTS sa_eval_item_trace (
    id          BIGSERIAL PRIMARY KEY,
    run_id      BIGINT  NOT NULL,
    item_id     BIGINT  NOT NULL,
    attempt     INT     NOT NULL DEFAULT 0,
    trace_id    VARCHAR(64),                         -- item root trace 的 traceId，关联 OpenObserve
    agent_trace JSONB,                               -- AgentTrace JSON（检索/工具调用轨迹）
    create_time TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_eval_item_trace_run ON sa_eval_item_trace (run_id);

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
COMMENT ON COLUMN sa_eval_metric.agent_trace IS '【已弃用·仅历史数据】agent 执行轨迹，2026-09-18 起改存 sa_eval_item_trace（原先每条 item 的轨迹被 9 个指标行各存一份）';

COMMENT ON TABLE sa_eval_item_trace IS '评测条目 agent 执行轨迹（每 run + item + attempt 一行，与 sa_eval_metric 解耦）';
COMMENT ON COLUMN sa_eval_item_trace.id IS '自增主键';
COMMENT ON COLUMN sa_eval_item_trace.run_id IS '所属运行 ID（sa_eval_run.id）';
COMMENT ON COLUMN sa_eval_item_trace.item_id IS '所属条目 ID（sa_eval_item.id）';
COMMENT ON COLUMN sa_eval_item_trace.attempt IS '评估序号：0=原始批，1/2/3…=第 n 次单条重评（与 sa_eval_metric.attempt 同口径）';
COMMENT ON COLUMN sa_eval_item_trace.trace_id IS '该 item root trace 的 traceId（关联 OpenObserve/Langfuse）';
COMMENT ON COLUMN sa_eval_item_trace.agent_trace IS 'AgentTrace JSON（检索/工具调用轨迹）';
COMMENT ON COLUMN sa_eval_item_trace.create_time IS '创建时间';

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

-- sa_agent_trace 演进：执行指纹三元组补齐（agent 在 paradigm 列；不变量 5——指纹进 trace/缓存/eval）
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS workflow_id VARCHAR(64);
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(32);
COMMENT ON COLUMN sa_agent_trace.workflow_id IS '执行的 Workflow id（执行指纹三元组之二）';
COMMENT ON COLUMN sa_agent_trace.prompt_hash IS '本次执行三层 prompt 快照的内容 hash（执行指纹三元组之三；改任一层 prompt 自动变化）';

-- ===== Agent 会话状态（运维诊断追问中断的跨轮次槽位状态）=====
-- ⚠️ 2026-09-19 起**已被 sa_agent_task 取代**（上下文四层结构：Conversation→Topic→Task→Attempt）。
-- 原因：本表把「一次诊断」等同于「一个会话」（conversation_id UNIQUE），于是诊断一出结论就置 DONE，
-- 下一轮消息既恢复不了也带不走槽位——正是"追问之下上下文全丢"的根因。
-- 代码侧 AgentSessionServiceImpl / Entity / Mapper 已删除（Business 包 session/ 不再存在）。
-- 表**保留不删**：仅为留存历史行；引擎侧旧会话 id（"ops-" + conversationId）同理成为孤儿，
-- 不再被任何路径 load。确认无需回溯后可在后续版本一并清理。
CREATE TABLE IF NOT EXISTS sa_agent_session (
    id              BIGSERIAL    PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL UNIQUE,               -- 会话（唯一：一会话至多一个进行中的 agent 状态）
    agent_type      VARCHAR(32)  NOT NULL,                      -- agent 范式：ops_diagnose
    stage           VARCHAR(32),                                -- 中断时所在骨架阶段（collect_slots/investigate_logs/...）
    status          VARCHAR(16)  NOT NULL,                      -- AWAITING_USER/RUNNING/DONE/EXPIRED
    slots           JSONB,                                      -- 已确认槽位（environment/interface/time/payload/error/symptoms）
    missing_slots   JSONB,                                      -- 中断时缺失的槽位名数组
    summary         TEXT,                                       -- 阶段产物一句话摘要（恢复时拼上下文，避免存完整对话）
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  sa_agent_session IS 'Agent 会话状态（追问中断跨轮次：槽位/阶段/状态机，TTL 过期）';
COMMENT ON COLUMN sa_agent_session.slots IS '已确认槽位键值对（jsonb）';
COMMENT ON COLUMN sa_agent_session.missing_slots IS '中断时缺失槽位名数组（jsonb）';

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

-- ===== 语义答案缓存（相似问题命中重放；失效靠 docver + 概率清理，无反馈机制）=====
CREATE TABLE IF NOT EXISTS sa_cache_answer (
    id                  BIGSERIAL    PRIMARY KEY,
    question            TEXT         NOT NULL,               -- 归一化问题（语义簇代表）
    question_embedding  VECTOR(1024) NOT NULL,               -- text-embedding-v3，同 spring_ai_store_vector
    answer              TEXT         NOT NULL,
    citations           JSONB,                               -- 引用溯源 JSON（可为 NULL）
    paradigm            VARCHAR(32)  NOT NULL,               -- agent 范式（knowledge/...）
    docver              BIGINT       NOT NULL,               -- 写入时文档版本号；查询 WHERE 匹配，重灌自然失配
    message_id          BIGINT,                               -- 关联 sa_message.id（未来点踩/badcase 回流挂点）
    hit_count           BIGINT       NOT NULL DEFAULT 0,     -- 语义命中次数（簇热度观测）
    create_time         TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time         TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_cache_ans_emb
    ON sa_cache_answer USING hnsw (question_embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_sa_cache_ans_paradigm ON sa_cache_answer (paradigm);

COMMENT ON TABLE  sa_cache_answer IS '语义答案缓存（pgvector 相似问题 → 命中重放答案）';
COMMENT ON COLUMN sa_cache_answer.docver IS '写入时文档版本号；查询只匹配当前版本，文档重灌后旧行等清理';

-- ===== Prompt 资产（2026-09-12 prompt 能力包 P1：资产化；bundle/release/binding 表为 P2 预建）=====

CREATE TABLE IF NOT EXISTS sa_prompt (
    id                 BIGSERIAL    PRIMARY KEY,
    prompt_key         VARCHAR(128) NOT NULL UNIQUE,               -- 逻辑 key（相对路径，如 agent/ops/resolve）
    current_version_id BIGINT,                                     -- 当前生效版本（sa_prompt_version.id）
    description        VARCHAR(512),
    create_time        TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time        TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  sa_prompt IS 'Prompt 资产（逻辑 key 与内容版本分离；运行态真相在 DB，classpath 为首次种子）';

CREATE TABLE IF NOT EXISTS sa_prompt_version (
    id           BIGSERIAL    PRIMARY KEY,
    prompt_id    BIGINT       NOT NULL,
    version_no   INT          NOT NULL,
    content      TEXT         NOT NULL,
    change_note  VARCHAR(512),
    create_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (prompt_id, version_no)
);
COMMENT ON TABLE  sa_prompt_version IS 'Prompt 版本历史（不可变只增不改：发新版本=新行，旧版本永不覆盖）';

CREATE TABLE IF NOT EXISTS sa_prompt_bundle (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL UNIQUE,                      -- 如 ops-standard / kb-finance
    description VARCHAR(512),
    agent_type  VARCHAR(32),                                      -- 限定适用骨架（null=通用包）
    create_time TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time TIMESTAMP    NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  sa_prompt_bundle IS 'Prompt 能力包（P2：基座+特化两层组合的组包主体）';

CREATE TABLE IF NOT EXISTS sa_prompt_bundle_release (
    id           BIGSERIAL    PRIMARY KEY,
    bundle_id    BIGINT       NOT NULL,
    release_no   INT          NOT NULL,
    items        JSONB        NOT NULL,                            -- {prompt_key: version_id} 全量快照
    change_note  VARCHAR(512),
    create_time  TIMESTAMP    NOT NULL DEFAULT NOW(),
    UNIQUE (bundle_id, release_no)
);
COMMENT ON TABLE  sa_prompt_bundle_release IS '能力包发布版本（P2：不可变快照，改包=新 release）';

CREATE TABLE IF NOT EXISTS sa_prompt_binding (
    id                BIGSERIAL   PRIMARY KEY,
    agent_type        VARCHAR(32) NOT NULL UNIQUE,                 -- 绑定主体（骨架类型）
    base_bundle_id    BIGINT,                                      -- 基座包（公共区 key）
    overlay_bundle_id BIGINT,                                      -- 特化包（骨架特有 key，null=无特化）
    update_time       TIMESTAMP   NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  sa_prompt_binding IS 'Prompt 绑定（P2：活包名——装配时解析各包当前 release 合并）';

-- ===== Prompt 组合与解析（2026-09-12 prompt 能力包 P2）=====

-- 语义缓存增加 prompt 口径维度：改 prompt（bundle 换版本）后旧语义行不再命中
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(24);
CREATE INDEX IF NOT EXISTS idx_sa_cache_ans_prompt ON sa_cache_answer (prompt_hash);

-- 会话粘住 prompt 组合：恢复追问轮精确重建当时的 release 对（bundleId:releaseNo|...，null=无绑定）
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS prompt_releases VARCHAR(256);

-- 人在环中 P3：会话自主档位（L1 多问我 / L2 默认 / L3 少问我；null = 按缺省 L2 处理）
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS autonomy_level VARCHAR(8);
COMMENT ON COLUMN sa_agent_session.autonomy_level IS '会话自主档位（L1/L2/L3，null=缺省 L2）';

-- 人在环中：诊断链 traceId（首轮生成，后续追问轮沿用）——一次诊断跨多轮也是一条链
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS chain_trace_id VARCHAR(64);
COMMENT ON COLUMN sa_agent_session.chain_trace_id IS '诊断链 traceId（同一次诊断跨轮沿用，轨迹不断链）';

-- ===== Agent 任务（上下文四层结构：Conversation → Topic → Task → Attempt）=====
-- Task = 工作边界（一个待解决的目标），Attempt = 执行边界（= 引擎会话）。
-- 与 sa_agent_session 的差别：后者一张表装了两个生命周期
--   Task 级 = slots/summary/autonomy_level/chain_trace_id
--   Attempt 级 = stage/missing_slots
-- 本表吸收 Task 级字段；Attempt 级状态归引擎会话（ops_engine_session/slots）。
-- 注意：**conversation_id 不设 UNIQUE**——一个会话可以先后有多个 Task（旧表的 UNIQUE 正是"一会话一会话"假设的化身）。
CREATE TABLE IF NOT EXISTS sa_agent_task (
    task_id         VARCHAR(64)  PRIMARY KEY,          -- UUID（与 conversation_id 解耦，故不派生）
    conversation_id VARCHAR(64)  NOT NULL,
    agent_type      VARCHAR(32)  NOT NULL,             -- agent 范式：ops_diagnose
    status          VARCHAR(16)  NOT NULL,             -- OPEN/RUNNING/SUSPENDED/CONCLUDED/CLOSED/ABANDONED
    slots           JSONB,                             -- 业务槽位（权威源仍是引擎槽位，这里是挂起时刻的投影）
    stage           VARCHAR(32),                       -- 挂起时所在节点（UI 展示"卡在哪一步"）
    summary         TEXT,                              -- 终态回填（第 5 步 findings 落地后改为其投影）
    autonomy_level  VARCHAR(8),                        -- 会话自主档位（L1/L2/L3，null=缺省 L2）
    chain_trace_id  VARCHAR(64),                       -- 诊断链 traceId（同一次诊断跨 attempt 沿用）
    attempt_count   INT          NOT NULL DEFAULT 0,   -- attemptId = "ops-" + task_id + "#" + attempt_count
    topic_id        VARCHAR(64),                       -- 所属主题（第 4 步 Topic 层，当前为空）
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    close_time      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sa_agent_task_conv  ON sa_agent_task (conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_sa_agent_task_chain ON sa_agent_task (chain_trace_id);
COMMENT ON TABLE  sa_agent_task IS 'Agent 任务（四层结构的工作边界：Conversation→Topic→Task→Attempt）';
COMMENT ON COLUMN sa_agent_task.task_id IS '任务 id（UUID，与 conversation_id 解耦）';
COMMENT ON COLUMN sa_agent_task.status IS 'OPEN/RUNNING/SUSPENDED/CONCLUDED/CLOSED/ABANDONED（CONCLUDED=已出结论待用户反应）';
COMMENT ON COLUMN sa_agent_task.attempt_count IS '已发起的 attempt 数；引擎会话 id = ops-<task_id>#<n>';
COMMENT ON COLUMN sa_agent_task.topic_id IS '所属主题（第 4 步 Topic 层，当前为空）';

-- 回收标记：引擎执行态已回收的任务不再参与每轮扫描（否则同一批终态任务会被反复取出来
-- 走一遍"查无此行"的空删除）。诊断结论不受影响——它存在 sa_agent_finding / sa_message / sa_agent_trace。
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS engine_reclaimed SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN sa_agent_task.engine_reclaimed IS '引擎执行态是否已回收（0=未回收；回收环节置 1）';

-- ===== Agent 诊断主张（Findings 台账）=====
-- 主张 = 一次诊断产出的、**可被单独否定**的断言。原料是四段式结论的各段落，由 FindingExtractor 纯解析抽取（不耗模型）。
-- 为什么不用一段文本存结论：文本无法局部否定——用户说"报文字段不对"时只能整段重来。
-- **只追加**：用 status 标记失效，不物理删除。
--   SUPERSEDED = 被新结论取代（正常演进） / RETRACTED = 被判定为错（否定）——两者审计含义不同，不可合并。
CREATE TABLE IF NOT EXISTS sa_agent_finding (
    finding_id      VARCHAR(64) PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,   -- 冗余自 task：按对话查主张是高频路径，省一次 join
    kind            VARCHAR(16) NOT NULL,   -- ROOT_CAUSE/CONSTRAINT/FIX/RISK/ANSWER
    claim           TEXT        NOT NULL,
    evidence        JSONB,                  -- [{label,value,source}]：指针+摘录，不是全文
    status          VARCHAR(16) NOT NULL,   -- ACTIVE/SUPERSEDED/RETRACTED
    attempt_no      INT         NOT NULL,   -- 由第几次 attempt 产出
    create_time     TIMESTAMP   NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sa_agent_finding_task  ON sa_agent_finding (task_id, status);
CREATE INDEX IF NOT EXISTS idx_sa_agent_finding_conv  ON sa_agent_finding (conversation_id, status);
COMMENT ON TABLE  sa_agent_finding IS '诊断主张台账（可被单独否定的断言 + 证据指针；只追加，状态标记失效）';
COMMENT ON COLUMN sa_agent_finding.kind IS 'ROOT_CAUSE/CONSTRAINT/FIX/RISK/ANSWER';
COMMENT ON COLUMN sa_agent_finding.evidence IS '证据指针数组 [{label,value,source}]——不存全文，用时按 ref 回查';
COMMENT ON COLUMN sa_agent_finding.status IS 'ACTIVE/SUPERSEDED（被取代）/RETRACTED（被判定为错）';
COMMENT ON COLUMN sa_agent_finding.attempt_no IS '由第几次 attempt 产出（对应引擎会话 ops-<taskId>#<n>）';
