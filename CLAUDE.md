# CLAUDE.md — customer-platform 项目导航

本文件为 Claude Code 提供项目上下文。

## 项目定位

基于 Spring AI 1.1 的 RAG 平台，[`15_ragent`](../15_ragent) 的完整重写版。原 ragent 的 infra-ai 角色（屏蔽模型供应商）由 **Spring AI 本身承担**（ChatClient / EmbeddingModel / VectorStore / ChatMemory / Advisor），**不建 infra-ai 模块**。

> **结构变更提示（2026-09-12）**：项目已按"框架 / 能力域 / 业务域"重构为 16 个 Maven 模块，
> 编排决策回归 `platform-business`，`platform-delivery` 只做通道交付（原单模块 `app/`、旧 `platform-chat` 均已不存在）。
> 结构与命名以 `docs/project-structure.md` 为准，跨会话交接看 `plan/2026-09-12-module-split-progress.md`。

## 多模块结构（2026-09-12 定稿，权威说明见 docs/project-structure.md）

> 本节已随"模块化拆分 + 编排回归业务层"重构刷新；旧 `app/` 单模块结构自 2026-09-12 起不再存在。

```
customer-platform（父聚合 POM，groupId com.jjx.customer，包根 com.jjx.customer.platform）
├── 框架层（零业务依赖，可独立成仓）
│   └── platform-agent-framework           单模块一体（包按概念分组，无 core / engine 之分）：
│                                          agent（Agent 与执行入口）/ workflow（流程声明与驱动）/ node（节点形态与执行器）/
│                                          tool（工具契约、注册与控制面）/ model / prompt / result / plan / route /
│                                          trace / guard / session / cache / config / exception
├── 基础设施层
│   ├── platform-common                    util / mybatis 类型处理器 / 分页 DTO
│   └── platform-shared                    配置属性 / Prompt 加载 / 模型与 HTTP 装配 + 跨域 SPI：
│                                          delivery · conversation · document · routing · intent · clarify
├── 能力域层
│   ├── platform-cache                     CacheStore / Redis 装配 / 频率准入
│   ├── platform-observe                   OpenObserve / Langfuse / TraceSampling
│   ├── platform-storage                   S3 对象存储 + 文件元数据
│   ├── platform-knowledge                 多通道检索 / 融合 / 重排 / 后处理 + RAG 生成（KnowledgeAnswerService）
│   ├── platform-prompt                    Prompt 资产 / 版本 / 能力包与绑定 / prompts 资源
│   └── platform-ingestion                 解析 / 分块 / 增强 / 富化 + 文档目录（DocumentCatalog 实现）
├── 业务层（调度 + 交付）
│   ├── platform-business                  ★ 业务/调度：orchestration（ChatOrchestrator 决策链）
│   │                                      + agents / workflows / tools / routing / runtime（DegradeGuard）
│   │                                      + session / trace / rest / agent/schemas 资源
│   ├── platform-delivery                  交付通道：sse（端口实现 + 事件协议）/ message（会话消息持久化）
│   │                                      / runtime（活动流注册表）/ service（入口薄壳）/ rest
│   ├── platform-mcp                       MCP 扩展工具来源
│   └── platform-eval                      评测：数据集 / 跑批 / 指标 / 对照 / 外部推送
└── 收口
    ├── platform-console                   控制台视图 / 全局异常 / ping / AgentRegistry
    └── platform-bootstrap                 唯一可执行装配点：启动类 / application*.yaml / sql / static / OpenApi
```

一次对话请求的调用链（先看这条链再看模块）：

```
POST /chat/stream
 └─ delivery.rest.ChatController → delivery.service.ChatService（入口薄壳，只传 SseEmitter）
     └─ business.orchestration.ChatOrchestrator<SseEmitter>.execute(...)
         ├─ 决策：会话恢复 → 归一化 → 规则短路 → 显式范式 → 指代悬空 → 意图分类 → 规则路由 → 分支
         ├─ ops 支路：FrameworkOpsRunner（agent + workflow）→ Outcome 分派（追问/直答/升级）
         └─ RAG 主线：ConversationStore.historyContext → QueryRewriter → FrameworkKnowledgeRunner
             （检索 = extension tool）→ knowledge.retrieval（多通道 + 两级答案缓存）
             → knowledge.answer.KnowledgeAnswerService（流式生成）→ orchestration.rag.RagContextAssembler
             → DeliveryPortFactory/SseDeliveryPort → sse.SseEventSender + message.ChatMessageWriter
```

依赖方向（硬约束）：bootstrap → 业务域 → 能力域 → shared → common；
业务域之间只经 shared 的 SPI（`DeliveryPort`/`DeliveryPortFactory`、`ConversationStore`、`DocumentCatalog`、
`routing`/`intent`/`clarify` 契约），不互相 import 实现。


## 关键架构决策

1. **Spring AI = infra-ai**：ChatClient 替代 LLMService、VectorStore.add 替代 EmbeddingService+VectorStoreService、PgVectorStore 替代 VectorStoreService、ChatMemory 替代 ConversationMemoryService。Rerank 无 Spring AI 统一抽象，自研 RerankClient 调百炼。
2. **节点引擎**：入库走 IngestionEngine（链式执行 + ConditionEvaluator 条件 + DB 配置驱动 sa_ingestion_pipeline/node），6 节点可插拔（按 nodeType 匹配 IngestionNode bean）。对应原 ragent 入库编排。
3. **查询链**：已实现 `ChatOrchestrator`（normalize→rewrite→intent→分支[ops诊断/nonQuery/emptyRetrieval/RAG]→agent编排→postprocess→stream，各步短路）。**意图路由优先级（2026-09-11，同日二次修正）**：会话恢复/traceId 短路 > **用户显式选择范式**（只认 `?agentChoice=`——新前端用户主动选择时才发送：ops_diagnose 直接进诊断跳过意图分类、knowledge 压制诊断劫持；`?agent=` 回归纯兼容语义仅管 RAG 链内编排，不参与意图路由——旧客户端默认携带的 agent=knowledge 不得被误判为显式选择）> **意图识别**（自动档=不带 agentChoice：needs_diagnose 且 confidence≥`rag.chat.diagnose-min-confidence`(0.6) 才进诊断，低置信度落回知识检索）> 默认知识检索。前端范式选择器 Auto 档（默认，chatParadigmOptions；显式选择时 agent+agentChoice 双发）。SSE 事件：trace→clarify（新，追问）→citations→message→meta，前端 chat.ts 对未知事件静默丢弃。
4. **Agent 体系（2026-08-28 重构，2026-09-10 workflow 引擎化，2026-09-11 包重组+工具分层，同日二次泛化）**：包结构按领域单向分层——core（SPI/工具循环内核/轨迹数据契约，SlotQuestion 出参渲染单元随 ClarifyRequest 落 core）← slot（纯槽位机制：SlotSpec 声明式 normalizer + Evaluator/Merger，**目录显式传参无隐式默认**）← workflow（引擎+StageSpec+Replan，**骨架零业务**：ReplanChecker 的 prompt 经 WorkflowDefinition.replanPromptKey 注入）← impl（Agent 装配，ops 目录/槽位/agent 聚在 impl.ops，新 workflow agent 复制此模式），registry 为顶层聚合（AgentRegistry/RagParadigm/Controller，LEGACY_ALIAS 唯一真身在 RagParadigm），trace/session 为独立持久化域（session TTL 配置已提通用层 rag.chat.agent.session-ttl-minutes，ops 段兼容 fallback），tools 按域分包（rag/ops/control/support），controller 随域走。**扩展故事：新增 workflow agent = ① @Component implements WorkflowAgent（definition 声明 agentType/槽位/抽槽 promptKey/阶段/replanPromptKey/maxAdjustRetries）② RagParadigm 加枚举值（刻意的路由注册点）③ 前端选项；pipeline/引擎/registry 零改**——pipeline 路由已泛化：resume 按 session.agentType 路由（空白回 ops 兼容存量数据）、显式 agentChoice 按 instanceof WorkflowAgent 分流（检索型范式仍走 RAG 链）、意图路由常量化。工具 = 自描述 AgentTool（name/description/JSON Schema/annotations），**来源统一走 ToolProvider**（BuiltinToolProvider 收 @Component bean + McpToolProvider 桥外部 MCP），ToolRegistry 只做注册与执行收口，分层边界由 ArchitectureFitnessTest 守（9 条规则：core/slot/workflow/tools/session/trace 方向 + 工具子系统内部分层，含包结构存在性断言防前缀失效空转）；循环 = ToolLoopEngine 原生 function calling 手动驱动（internalToolExecutionEnabled=false），tool-call-mode: json 为降级逃生门（JsonToolCallAdapter 与引擎同包，出环前置检查共享 LoopExitChecks）；范式 = knowledge（eval 基准）/ops_diagnose/react_loop（对照轴）。旧 naive/react 的 ?agent= 值经 alias 映射。能力清单端点 GET /agent/registry（旧字段 type/label/stages/tools 稳定，2026-09-13 增强 workflowId/slots/stageDetails/policy/agent+workflowPromptKeys 两层拆分；管理后台「Agent 清单」页与范式选择器共用，Agent×Workflow 组合结构可视化）。
5. **手写 JDBC，不走 Spring AI VectorStore**：入库 `IndexerNode` / 查询 `VectorSearchChannel` 均手写 JDBC 直写 pgvector 表（cosine + HNSW），**未用** Spring AI `VectorStore`/`Document` 抽象。表格原文进 content、key-value 仅算 embedding 后丢弃，**不塞 `metadata["raw_content"]`**（旧描述未实现）。
6. **表前缀 sa_**：业务表 sa_document / sa_doc_collection / sa_ingestion_task / sa_ingestion_task_node / sa_ingestion_pipeline / sa_ingestion_pipeline_node / sa_conversation / sa_message / sa_agent_trace / sa_agent_session / sa_eval_dataset / sa_eval_item / sa_eval_run / sa_eval_metric（spring.sql.init 幂等建表），避免与同库 15_ragent 表冲突。向量表 spring_ai_store_vector 由建表脚本管理（手写 JDBC 读写）。
7. **ingestionChatClient 独立 bean**：裸 ChatClient（不带 Advisor），给 Enhancer/Enricher/意图分类/槽位抽取/replan 纯 LLM 调用用；ragChatClient 给查询回答用（无 Advisor 挂载）。
8. **缓存层（2026-09-10，Redis 持久化 + Sentinel-lite 频率策略 + 动态限流 + 语义缓存）**：`rag.cache.*` 配置（总开关+每层 enabled/ttl/admission-threshold）。五层：意图（仅 conf≥0.7）/检索结果/日志工具（trace_id 24h，时间窗 60s 分钟桶 key）/精确答案（重放 citations→message→meta）/查询 embedding。失效 = docver 版本号（入库挂 IngestionEngineService 双 INCR `_all`+collectionId，key 拼版本自然换，不做 keys 扫删）+ TTL 兜底。装饰器 @Primary（CachingIntentClassifier/CachingRetrievalEngine），**检索缓存 key 必含 SearchContext 全参数**（eval 参数扫描正确性硬约束）；LogDiagnoseService 故意注入具体类绕过缓存保实时。**频率策略**（统计/决策/编排三分离：FrequencyTracker 只计数、CacheFrequencyPolicy 纯函数决策）：**2026-09-11 统计器进程内化**（Sentinel LeapArray 朴素版：每 key 16 槽环形时间桶，槽打包「桶纪元<<32|计数」，写 O(1) CAS、求和 O(16)；`rag.cache.freq-max-keys` 上限 + 冷 key sweep）——原 Redis INCR+EXPIRE 每请求固定多 4 次 RTT，统计开销反超缓存收益，且 Redis 故障连带频率策略失效；进程内后热路径零 Redis 调用、降级期准入照常（多实例计数偏低/重启清零为已知取舍，单实例无碍）。窗口内出现满阈值才写（retrieval/answer 默认 2，防长尾一次性查询污染；eval 走 metadata.intent=EVAL 旁路），命中且窗口计数达热度档（≥5 ×4 / ≥20 ×10）延长 TTL——key 含 docver 所以 TTL 只是内存旋钮，延长零正确性风险。**动态限流（熔断联动）**：RedisHealth 共享断路器（连续 5 败 → OPEN 冷却 30s → 半开探测恢复），RedisCacheStore/DocumentVersionStamp 两处共用——慢死形态（连接挂起×每请求 7-8 次操作×3s）等待归零；DegradeGuard 在降级期对昂贵路径（LLM 流式/ops 诊断）限并发 `rag.chat.degrade-limit: 8`（健康期零成本直通，检索侧由 ragContextExecutor 天然 bulkhead），拒绝发礼貌 SSE 提示；connect-timeout 收紧 1s。**语义缓存**：sa_cache_answer（pgvector 1024 维 + HNSW cosine），exact miss 后按归一化问题 embedding 相似度 ≥0.95 命中重放（同范式+同 docver 进 WHERE）；失效靠 docver+概率清理（TTL/容量），无反馈机制；写入不做频率准入（字面不同的重复问题正是靶子）；QueryEmbedder 统一查询 embedding（向量通道与语义缓存共享缓存）。Redis 侧 maxmemory+volatile-lru 兜底（只逐出带 TTL 的缓存 key，MQ stream/docver 无 TTL 不受影响）。

## 运行 / 验证

- 端口 **9081**，context-path `/api/rag`
- JDK：`D:/03_developtools/02_jdk/ms-21.0.11`（02_jdk 是多版本聚合目录，须指到子目录；原 temurin-21.0.11 目录已空，改指 ms-21.0.11）
- IDEA 启动：**Shorten command line = JAR manifest**（依赖多，默认超 Windows 命令行长度限制）
- spring.sql.init 启动自动建业务表（sa_* + 向量表 spring_ai_store_vector，幂等）
- Knife4j：`/api/rag/doc.html`；前端：`cd frontend && npm run dev` → `localhost:5173`
- traceId：日志每行带 `[traceId,spanId]`（Micrometer OTel），OTLP 可发 OpenObserve
- Docker 部署（本地/服务器 compose、端口、账号、表结构迁移、运维）见 `docker/README.md`；打包→部署分步手册见 `docs/deploy.md`
- 提交安全铁律：文档/配置里绝不写真实 IP、账号、密码、key——示例用 xxx/<占位>/${VAR}，真实值只进 .env（.env/.env.local/.env.prod 全 gitignore）；pre-commit 钩子 scripts/check-secrets.sh 自动拦截（clone 后 git config core.hooksPath .githooks 启用）

## 进度与剩余路线（2026-09-10 核查）

**主干已全部落地**（P/Q 路线 2026-08-08 核查 + 后续增量）：

- **入库（P2c–P7 完成）**：Parser 全套（Tika/MD/CSV/MinerU 9 类 + Selector）、Fetcher、Chunker（Block-Aware + StructureAware）、Indexer（手写 JDBC pgvector）、Enricher（虚拟线程并行 LLM）、Storage（Local + S3/RustFS）、MQ（RocketMQ + Redis Stream）、VLM（qwen-vl）、节点日志持久化（NodeLog→`sa_ingestion_task_node`）、文档集合（sa_doc_collection）—— 全部真实。
- **查询（Q1–Q5 完成）**：`ChatOrchestrator` 真链式、QueryNormalizer+QueryRewriter、IntentClassifier、多通道检索（Vector + Keyword SQL/BM25）、后处理（Dedup→RRF Fusion→Rerank[百炼]）—— 全部真实。
- **2026-09-10 重构**：WorkflowEngine 引擎化（决策 4）+ 编排层三组件拆分 + Redis 五层缓存（决策 8）。
- **2026-09-12 分层重构**：16 模块（框架/能力域/业务域）+ 交付端口 SPI + 编排决策回归 `business.orchestration.ChatOrchestrator`。
- **可观测性**：telemetry 已抽独立 starter（外部 jar `com.jjx.ai.llmobservability`：@TelemetryStep / TelemetryTemplate / TelemetryLogger），app 侧仅 `config/telemetry/TelemetryDimensions` 做维度提取；查询链/入库链双挂 OpenObserve。
- **评测 Phase 1-2 已落地**：黄金集（sa_eval_* 四表）+ Recall@k / Precision@k / MRR / nDCG + 异步跑批 + LiveRagImporter 线上回流 + 前端 eval 面板与多范式对照（`/api/rag/#/admin/compare`）。
- **2026-09-10 评测通用化 + Langfuse 推送**：eval/framework 三件套（EvalSample 四元组 / EvalScorer / EvalResultSink，@Component 即注册）+ 上下文级比对（context_recall/precision：线上同款 RagContext 的 citations vs 标准召回，comment 带文件名对照）+ answer judge scorer 化 + **评测结果推 Langfuse 完整 dataset run**（sa_eval_item 惰性同步为 Langfuse dataset 条目，runName=eval-run-{runId}，指标 score 挂 traceId+datasetRunId，UI 可跨 run 对比；rag.eval.langfuse.* 配置，无凭据自动 no-op）。评测的答案生成输入改用线上同款 RagContextAssembler（评测即真实）。

**仍 stub / 被禁的零散项**（低成本收掉）：

- `WebSearchChannel` —— 桩，`search()` 返回空，未接搜索源
- `EnhancerNode` —— `ENHANCER_ENABLED=false` 空跑
- `SiblingExpansionPostProcessor` —— 代码完整但 `isEnabled()=false`
- `FixedSize` 分块 —— ChunkerNode 自动改写成 STRUCTURE_AWARE，实际只一种切分
- `IntentTree` / `QueryTermMapping` —— 未建

**下一阶段方向（平台级，待选型）**：

1. **评测深化**：~~检索指标 + 答案侧打分~~ 已落地（含 Langfuse dataset run 推送，见进度节）；剩 must_contain/rubric 维度启用、线上对话自动回流评测集（现仅 LiveRAG 导入）、评测报告定期推送。
2. **调优 + 反馈闭环**：RRF 权重 / 相似度阈值 / topK / 分块粒度做成可实验参数；前端点赞点踩、badcase 标注回流评测集。
3. **Agentic / 多步检索**：查询分解、检索结果自评、不达标再检索（self-RAG / corrective RAG），扩展现有 intent 分发。
4. **权限与多租户**：文档级 ACL（谁能检索哪份文档），企业场景刚需。
5. **缓存层**：~~embedding 缓存 + 语义查询缓存~~ **已全部落地**（2026-09-10，见决策 8：五层 Redis 缓存 + Sentinel-lite 频率准入/热度 TTL + 熔断联动动态限流 + pgvector 语义答案缓存）。反馈类失效（点踩回流）明确不做；可演进项：双窗口趋势判定（升降温自适应准入）、断路器恢复 AIMD 渐进放量。
6. **向量增量维护**：upsert/delete，文档更新/删除时同步（当前只增不改）。
7. **知识图谱 / GraphRAG**：结构化关系增强，补纯向量检索的多跳推理短板。
8. **前端管理后台**：pipeline 可视化编排、badcase 标注界面（eval 看板/对照面板/console API 已有骨架）。
