-- ============================================================
-- 表结构对齐脚本（由 scripts/deploy-prepare.py 从现状库生成，对旧库执行，幂等）
--
-- 为什么需要：init.sql 每次启动都跑，但列级迁移只覆盖「写成 ALTER 的列」。
--   仅存在于建表体里的列（如 sa_eval_item 的 difficulty/irt_diff/irt_disc/acs/acs_std）
--   不会被补到**已存在**的表上，随后新代码查询即报 column does not exist。
--
-- 生成方式：pg_catalog（format_type 取精确类型，pgvector 列才是 vector(1024) 而非 user-defined）；
--   只针对 sa_* 业务表；**刻意不带 NOT NULL**（旧表有数据时 NOT NULL 无默认会让 ADD COLUMN 失败中止）。
-- 生成时间：2026-09-20
-- 用法：docker exec -i <pg容器> psql -U <用户> -d <库> -v ON_ERROR_STOP=1 < scripts/ensure-schema.sql
-- ============================================================

-- sa_agent_finding
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS finding_id VARCHAR(64);
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS task_id VARCHAR(64);
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS kind VARCHAR(16);
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS claim TEXT;
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS evidence JSONB;
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS attempt_no INTEGER;
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_agent_finding ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_agent_session
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS agent_type VARCHAR(32);
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS stage VARCHAR(32);
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS slots JSONB;
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS missing_slots JSONB;
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS summary TEXT;
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS prompt_releases VARCHAR(256);
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS autonomy_level VARCHAR(8);
ALTER TABLE sa_agent_session ADD COLUMN IF NOT EXISTS chain_trace_id VARCHAR(64);

-- sa_agent_task
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS task_id VARCHAR(64);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS agent_type VARCHAR(32);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS slots JSONB;
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS stage VARCHAR(32);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS summary TEXT;
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS autonomy_level VARCHAR(8);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS chain_trace_id VARCHAR(64);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS attempt_count INTEGER DEFAULT 0;
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS topic_id VARCHAR(64);
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS close_time TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS engine_reclaimed SMALLINT DEFAULT 0;
ALTER TABLE sa_agent_task ADD COLUMN IF NOT EXISTS qa_count INTEGER DEFAULT 0;

-- sa_agent_trace
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS message_id BIGINT;
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32);
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS question TEXT;
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS steps JSONB;
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS llm_call_count INTEGER;
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS total_latency_ms BIGINT;
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS workflow_id VARCHAR(64);
ALTER TABLE sa_agent_trace ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(32);

-- sa_cache_answer
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS question TEXT;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS question_embedding VECTOR(1024);
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS answer TEXT;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS citations JSONB;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32);
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS docver BIGINT;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS message_id BIGINT;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS hit_count BIGINT DEFAULT 0;
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_cache_answer ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(24);

-- sa_chunk_parent
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS parent_key VARCHAR(128);
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS doc_id VARCHAR(64);
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS collection_id BIGINT;
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS doc_name VARCHAR(255);
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS outline_path VARCHAR(512);
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE sa_chunk_parent ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_conversation
ALTER TABLE sa_conversation ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_conversation ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
ALTER TABLE sa_conversation ADD COLUMN IF NOT EXISTS title VARCHAR(255) DEFAULT '新对话'::character varying;
ALTER TABLE sa_conversation ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_conversation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_doc_collection
ALTER TABLE sa_doc_collection ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_doc_collection ADD COLUMN IF NOT EXISTS name VARCHAR(128);
ALTER TABLE sa_doc_collection ADD COLUMN IF NOT EXISTS description VARCHAR(512);
ALTER TABLE sa_doc_collection ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_doc_collection ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_doc_collection ADD COLUMN IF NOT EXISTS deleted SMALLINT DEFAULT 0;

-- sa_document
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS doc_id VARCHAR(64);
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS name VARCHAR(255);
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS mime_type VARCHAR(128);
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS source_type VARCHAR(32);
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS source_location VARCHAR(512);
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS chunk_count INTEGER DEFAULT 0;
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'PENDING'::character varying;
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS error_msg TEXT;
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_document ADD COLUMN IF NOT EXISTS collection_id BIGINT;

-- sa_eval_dataset
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS name VARCHAR(128);
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS item_count INTEGER DEFAULT 0;
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS deleted SMALLINT DEFAULT 0;
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_eval_dataset ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_eval_item
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS dataset_id BIGINT;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS item_key VARCHAR(64);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS category VARCHAR(32);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS question TEXT;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS query_embedding VECTOR(1024);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS expected_doc_ids JSONB;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS expected_answer TEXT;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS must_contain JSONB;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS rubric TEXT;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS source VARCHAR(16) DEFAULT 'builtin'::character varying;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS enabled SMALLINT DEFAULT 1;
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS difficulty VARCHAR(8);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS irt_diff NUMERIC(8,4);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS irt_disc NUMERIC(8,4);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS acs NUMERIC(8,4);
ALTER TABLE sa_eval_item ADD COLUMN IF NOT EXISTS acs_std NUMERIC(8,4);

-- sa_eval_item_trace
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS item_id BIGINT;
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS attempt INTEGER DEFAULT 0;
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS agent_trace JSONB;
ALTER TABLE sa_eval_item_trace ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_eval_metric
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS run_id BIGINT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS item_id BIGINT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS question TEXT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS retrieved_doc_ids JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS retrieved_doc_names JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS metric_name VARCHAR(32);
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS score NUMERIC(6,4);
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS detail JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS latency_ms BIGINT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS attempt INTEGER DEFAULT 0;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS remark VARCHAR(500);
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS rewrite BOOLEAN DEFAULT false;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS expected_doc_ids JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS expected_doc_names JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS agent_trace JSONB;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32) DEFAULT 'naive'::character varying;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS category VARCHAR(32);
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS expected_answer TEXT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS generated_answer TEXT;
ALTER TABLE sa_eval_metric ADD COLUMN IF NOT EXISTS per_question BOOLEAN DEFAULT false;

-- sa_eval_run
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS dataset_id BIGINT;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS status VARCHAR(32);
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS total INTEGER;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS done INTEGER DEFAULT 0;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS param_snapshot JSONB;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS aggregate_metrics JSONB;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_eval_run ADD COLUMN IF NOT EXISTS paradigm VARCHAR(32) DEFAULT 'naive'::character varying;

-- sa_ingestion_pipeline
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS name VARCHAR(128);
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS description VARCHAR(512);
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS created_by VARCHAR(64) DEFAULT 'system'::character varying;
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64) DEFAULT 'system'::character varying;
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_ingestion_pipeline ADD COLUMN IF NOT EXISTS deleted SMALLINT DEFAULT 0;

-- sa_ingestion_pipeline_node
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS pipeline_id BIGINT;
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS node_id VARCHAR(64);
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS node_type VARCHAR(32);
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS next_node_id VARCHAR(64);
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS settings_json JSONB;
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS condition_json JSONB;
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS created_by VARCHAR(64) DEFAULT 'system'::character varying;
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS updated_by VARCHAR(64) DEFAULT 'system'::character varying;
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_ingestion_pipeline_node ADD COLUMN IF NOT EXISTS deleted SMALLINT DEFAULT 0;

-- sa_ingestion_task
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS task_id VARCHAR(64);
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS doc_id VARCHAR(64);
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'PENDING'::character varying;
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS progress INTEGER DEFAULT 0;
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS error_msg TEXT;
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_ingestion_task ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_ingestion_task_node
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS task_id VARCHAR(64);
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS pipeline_id BIGINT;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS node_id VARCHAR(64);
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS node_type VARCHAR(32);
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS node_order INTEGER DEFAULT 0;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS status VARCHAR(32);
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS duration_ms BIGINT;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS message TEXT;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS error_message TEXT;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS output_json JSONB;
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_ingestion_task_node ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_message
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS role VARCHAR(16);
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS citations JSONB;
ALTER TABLE sa_message ADD COLUMN IF NOT EXISTS clarify JSONB;

-- sa_prompt
ALTER TABLE sa_prompt ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_prompt ADD COLUMN IF NOT EXISTS prompt_key VARCHAR(128);
ALTER TABLE sa_prompt ADD COLUMN IF NOT EXISTS current_version_id BIGINT;
ALTER TABLE sa_prompt ADD COLUMN IF NOT EXISTS description VARCHAR(512);
ALTER TABLE sa_prompt ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_prompt ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_prompt_binding
ALTER TABLE sa_prompt_binding ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_prompt_binding ADD COLUMN IF NOT EXISTS agent_type VARCHAR(32);
ALTER TABLE sa_prompt_binding ADD COLUMN IF NOT EXISTS base_bundle_id BIGINT;
ALTER TABLE sa_prompt_binding ADD COLUMN IF NOT EXISTS overlay_bundle_id BIGINT;
ALTER TABLE sa_prompt_binding ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_prompt_bundle
ALTER TABLE sa_prompt_bundle ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_prompt_bundle ADD COLUMN IF NOT EXISTS name VARCHAR(64);
ALTER TABLE sa_prompt_bundle ADD COLUMN IF NOT EXISTS description VARCHAR(512);
ALTER TABLE sa_prompt_bundle ADD COLUMN IF NOT EXISTS agent_type VARCHAR(32);
ALTER TABLE sa_prompt_bundle ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
ALTER TABLE sa_prompt_bundle ADD COLUMN IF NOT EXISTS update_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_prompt_bundle_release
ALTER TABLE sa_prompt_bundle_release ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_prompt_bundle_release ADD COLUMN IF NOT EXISTS bundle_id BIGINT;
ALTER TABLE sa_prompt_bundle_release ADD COLUMN IF NOT EXISTS release_no INTEGER;
ALTER TABLE sa_prompt_bundle_release ADD COLUMN IF NOT EXISTS items JSONB;
ALTER TABLE sa_prompt_bundle_release ADD COLUMN IF NOT EXISTS change_note VARCHAR(512);
ALTER TABLE sa_prompt_bundle_release ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();

-- sa_prompt_version
ALTER TABLE sa_prompt_version ADD COLUMN IF NOT EXISTS id BIGINT;
ALTER TABLE sa_prompt_version ADD COLUMN IF NOT EXISTS prompt_id BIGINT;
ALTER TABLE sa_prompt_version ADD COLUMN IF NOT EXISTS version_no INTEGER;
ALTER TABLE sa_prompt_version ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE sa_prompt_version ADD COLUMN IF NOT EXISTS change_note VARCHAR(512);
ALTER TABLE sa_prompt_version ADD COLUMN IF NOT EXISTS create_time TIMESTAMP WITHOUT TIME ZONE DEFAULT now();
