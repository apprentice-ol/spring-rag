# CLAUDE.md — springai-rag 项目导航

本文件为 Claude Code 提供项目上下文。

## 项目定位

基于 Spring AI 1.1 的 RAG 平台，[`15_ragent`](../15_ragent) 的完整重写版。原 ragent 的 infra-ai 角色（屏蔽模型供应商）由 **Spring AI 本身承担**（ChatClient / EmbeddingModel / VectorStore / ChatMemory / Advisor），**不建 infra-ai 模块**。

## 多模块结构

```
springai-rag（父聚合 POM）
├── rag-common/     通用基础
│   ├── src/main/java/com/nageoffer/ai/rag/common/
    ├── pom.xml           依赖：gson + hutool
│       ├── exception/     ClientException / ServiceException
│       ├── context/       UserContext
│       └── util/          TextCleanupUtil / PromptTemplateRenderer / FileTypeDetector
│                        + LLMResponseCleaner / JsonResponseParser
└── rag-core/       核心业务（依赖 rag-common）
    ├── src/main/java/com/nageoffer/ai/rag/
        ├── web/            ★ 统一前端/API 层
        │   ├── chat/         ChatController（/chat/stream SSE）
        │   └── ingestion/    IngestionController（/docs/upload）
        │                    + IngestionPipelineController（/ingestion/pipelines CRUD）
        ├── config/         ★ 配置层
        │   ├── properties/    ChatProperties / IngestionProperties / RagStorageProperties / VlmProperties
        │   ├── ChatClientConfig / OkHttpConfig / OpenApiConfig / S3Config
        │   └── VlmClient        ← 原 core/vlm/
        ├── ingestion/      ★ 入库模块（纯业务）
        │   ├── parser/       Block 模型 + 解析器（Tika/MD/Csv/MinerU）
        │   ├── chunk/        VectorChunk + Chunker 全套（Block-Aware/FixedSize/StructureAware）
        │   ├── engine/       IngestionEngine / ConditionEvaluator / NodeOutputExtractor
        │   ├── node/         IngestionNode + 6节点（Fetcher/Parser/Chunker/Enhancer/Enricher/Indexer）
        │   ├── domain/       context / pipeline / result / enums / settings
        │   ├── dao/          entity / mapper / handler（JsonbTypeHandler）
        │   ├── service/      IngestionService / IngestionPipelineService / IngestionEngineService
        │   ├── mq/           IngestionProducer/Consumer/Message（RocketMQ）
        │   └── storage/      FileStorageService / ObjectStorageClient / StoredFileDTO
        │       └── impl/      DefaultFileStorageService / LocalFileStorageService / S3ObjectStorageClient
        ├── chat/           ★ 查询模块（纯业务）
        │   ├── retrieval/      SearchChannel / SearchContext / RetrievedChunk / VectorSearchChannel
        │   ├── postprocessor/  SearchResultPostProcessor / DeduplicationPostProcessor
        │   └── service/        ChatService（单路 MVP → Q5 StreamChatPipeline）
        ├── store/mapper/   DocumentMapper / IngestionTaskMapper / IngestionTaskNodeMapper
        └── model/          entity(DocumentEntity/IngestionTaskEntity/IngestionTaskNodeEntity) / dto(IngestionResult)
```

## 关键架构决策

1. **Spring AI = infra-ai**：ChatClient 替代 LLMService、VectorStore.add 替代 EmbeddingService+VectorStoreService、PgVectorStore 替代 VectorStoreService、ChatMemory 替代 ConversationMemoryService。Rerank 无 Spring AI 统一抽象，自研 RerankClient 调百炼。
2. **节点引擎**：入库走 IngestionEngine（链式执行 + ConditionEvaluator 条件 + DB 配置驱动 sa_ingestion_pipeline/node），6 节点可插拔（按 nodeType 匹配 IngestionNode bean）。对应原 ragent 入库编排。
3. **查询链**：已实现 `StreamChatPipeline`（normalize→rewrite→intent→分支[diagnose/nonQuery/emptyRetrieval/RAG]→retrieve→postprocess→stream，各步短路），QuestionAnswerAdvisor 单路方案已废弃。代码在 `chat/`（非 `web/chat/`）。
4. **手写 JDBC，不走 Spring AI VectorStore**：入库 `IndexerNode` / 查询 `VectorSearchChannel` 均手写 JDBC 直写 pgvector 表（cosine + HNSW），**未用** Spring AI `VectorStore`/`Document` 抽象。表格原文进 content、key-value 仅算 embedding 后丢弃，**不塞 `metadata["raw_content"]`**（旧描述未实现）。
5. **表前缀 sa_**：业务表 sa_document/sa_ingestion_task/sa_ingestion_pipeline/sa_ingestion_pipeline_node，避免与同库 15_ragent 表冲突。向量表 spring_ai_store 由 PgVectorStore 自动建。
6. **ingestionChatClient 独立 bean**：裸 ChatClient（不带 Advisor），给 Enhancer/Enricher/VLM 纯 LLM 调用用；ragChatClient 带 QuestionAnswerAdvisor+MemoryAdvisor 给查询用。

## 运行 / 验证

- 端口 **9081**，context-path `/api/rag`
- JDK：`D:/03_developtools/02_jdk/ms-21.0.11`（02_jdk 是多版本聚合目录，须指到子目录；原 temurin-21.0.11 目录已空，改指 ms-21.0.11）
- IDEA 启动：**Shorten command line = JAR manifest**（依赖多，默认超 Windows 命令行长度限制）
- spring.sql.init 启动自动建业务表（sa_*，幂等）；PgVectorStore 自动建 spring_ai_store
- Knife4j：`/api/rag/doc.html`；前端：`cd frontend && npm run dev` → `localhost:5173`
- traceId：日志每行带 `[traceId,spanId]`（Micrometer OTel），OTLP 可发 OpenObserve
- Docker 部署（本地/服务器 compose、端口、账号、表结构迁移、运维）见 `docker/README.md`；打包→部署分步手册见 `docs/deploy.md`
- 提交安全铁律：文档/配置里绝不写真实 IP、账号、密码、key——示例用 xxx/<占位>/${VAR}，真实值只进 .env（.env/.env.local/.env.prod 全 gitignore）；pre-commit 钩子 scripts/check-secrets.sh 自动拦截（clone 后 git config core.hooksPath .githooks 启用）

## 进度与剩余路线（2026-08-08 全链路核查）

**P/Q 主干路线已全部落地**，plan 文件 `rippling-stargazing-reddy.md` 与本节旧描述已过时：

- **入库（P2c–P7 完成）**：Parser 全套（Tika/MD/CSV/MinerU 9 类 + Selector）、Fetcher、Chunker（Block-Aware + StructureAware）、Indexer（手写 JDBC pgvector）、Enricher（虚拟线程并行 LLM）、Storage（Local + S3/RustFS）、MQ（RocketMQ + Redis Stream）、VLM（qwen-vl）、节点日志持久化（NodeLog→`sa_ingestion_task_node`）—— 全部真实。
- **查询（Q1–Q5 完成）**：`StreamChatPipeline` 真链式、QueryNormalizer+QueryRewriter、IntentClassifier、多通道检索（Vector + Keyword SQL/BM25）、后处理（Dedup→RRF Fusion→Rerank[百炼]）—— 全部真实。
- **可观测性**：`config/telemetry/` 10 类，`@TraceStep` + RagTelemetry/RagLogger，查询链/入库链双挂 OpenObserve。

**仍 stub / 被禁的零散项**（低成本收掉）：

- `WebSearchChannel` —— 桩，`search()` 返回空，未接搜索源
- `EnhancerNode` —— `ENHANCER_ENABLED=false` 空跑
- `SiblingExpansionPostProcessor` —— 代码完整但 `isEnabled()=false`
- `FixedSize` 分块 —— ChunkerNode 自动改写成 STRUCTURE_AWARE，实际只一种切分
- `IntentTree` / `QueryTermMapping` —— 未建

**下一阶段方向（平台级，待选型）**：

1. **评测体系（RAG Eval）—— 当前最大缺口**：黄金问答集 + 检索召回率（Recall@k）/ 答案忠实度（faithfulness）/ 幻觉率自动打分（ragas / DeepEval 思路或 LLM-as-judge）。是后面所有调优的前提。
2. **调优 + 反馈闭环**：RRF 权重 / 相似度阈值 / topK / 分块粒度做成可实验参数；前端点赞点踩、badcase 标注回流评测集。
3. **Agentic / 多步检索**：查询分解、检索结果自评、不达标再检索（self-RAG / corrective RAG），扩展现有 intent 分发。
4. **权限与多租户**：文档级 ACL（谁能检索哪份文档），企业场景刚需。
5. **缓存层**：embedding 缓存（省模型调用）+ 语义查询缓存（命中直接回答案）。
6. **向量增量维护**：upsert/delete，文档更新/删除时同步（当前只增不改）。
7. **知识图谱 / GraphRAG**：结构化关系增强，补纯向量检索的多跳推理短板。
8. **前端管理后台**：pipeline 可视化编排、badcase 标注界面（配合方向 1/2）。
