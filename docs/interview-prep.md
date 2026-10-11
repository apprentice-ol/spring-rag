# 面试准备文档 — springai-rag（customer-platform）

> 基于 2026-09-22 真实代码核查撰写（8 路并行代码探索交叉验证），文档口径以当前代码为准，
> 旧文档中已失效的类名/机制均已修正并标注。
> 使用方式：第 0 章背熟 → 第 1 章建立全景 → 第 2~10 章按面试方向选深 → 第 11~12 章冲刺理念与 Q&A。

---

## 0. 电梯陈述（先背这个）

### 30 秒版本

> 我主导了一个企业级 AI Agent 平台：Java 21 + Spring Boot 3.5 + Spring AI 1.1，16 个 Maven 模块分层架构。
> 两条主线：**RAG 知识问答**（多格式入库 → 多通道检索 → RRF 融合 → 重排 → 流式带引用回答）和
> **运维诊断 Agent**（图范式工作流引擎，槽位收集 → 三阶段工具循环 → 人在环中挂起/恢复）。
> 内核是自研的零业务依赖 agent 框架（拦截器/过滤器/守卫三层横切、游标驱动的节点主循环、检查点持久化、
> 可挂起可恢复），配五层缓存 + 熔断降级、评测闭环（Recall/MRR/nDCG + Langfuse）、全链路可观测（OTel → OpenObserve）。

### 3 分钟版本（按"问题 → 决策 → 结果"展开）

1. **业务问题**：企业既要高质量知识问答，又要可扩展的 Agent 编排（诊断排障、澄清追问、升级转人工），两者共用模型、检索、观测底座。
2. **架构决策**：
   - Spring AI 只当 infra-ai（ChatClient/EmbeddingModel），**不建 infra-ai 模块**；但向量读写手写 JDBC pgvector（HNSW + cosine），不走 Spring AI VectorStore 抽象——为了控制集合过滤、GUC 调优和批量写入。
   - 自研图范式 agent 内核（platform-agent-core，包根 com.agentframework，约 240 文件，零业务依赖可独立成仓）：声明式图（节点/条件边/Region 循环/动态边白名单）+ 游标驱动主循环 + 逐节点 checkpoint，天然支持**挂起-恢复**（HITL 的根基）。
   - 单引擎三 Agent（ops_diagnose / knowledge / react_loop），编排决策收敛在 ChatOrchestrator 决策链，交付走 DeliveryPort SPI（SSE 只是一种实现）。
3. **关键技术点**：多通道检索 + RRF + qwen3-rerank 分批精排（分数随请求长度衰减的实测对策）；子块检索父块生成（对照实验期望 +0.103）；五层 Redis 缓存 + docver 版本化失效 + LeapArray 频率准入 + 语义缓存（pgvector ≥0.95）；Redis 熔断器联动动态限流；Prompt 资产化（版本/能力包/三层增强，内容 hash 进缓存 key 自动失效）；评测即真实（线上同款 RagContextAssembler）；Langfuse dataset run 闭环。
4. **结果口径**：16 模块全量测试绿（约 190+ 用例）；检索指标 Recall@5 / MRR / nDCG 全套可回归；一次诊断可跨多轮追问不断链（chainTraceId 续链）；Redis 故障时主链路零感知旁路。

### 数字口径（防讲错）

| 项 | 值 |
|---|---|
| Maven 模块 | 16（框架 1 + 基础 2 + 能力域 6 + 业务 4 + 收口 2 + 父 POM） |
| agent 内核 | platform-agent-core 约 240 个 Java 文件，零业务依赖，内核自带 152 测试 |
| Prompt 资产 | 31 个 key（rag/agent/ingestion/eval/vlm 五链路） |
| 业务表 | sa_ 前缀 20+ 张 + 引擎表 ops_engine_session/ops_engine_slots + 向量表 spring_ai_store_vector |
| Agent 范式 | 3 个（ops_diagnose / knowledge / react_loop，旧别名 naive/react 自动映射） |
| 检索通道 | 向量（pgvector HNSW）+ 关键词（ParadeDB BM25，生产实配）+ Web（桩） |
| 缓存 | 5 层 Redis + 1 层 pgvector 语义缓存 |

---

## 1. 全景：定位与 16 模块分层架构

### 1.1 项目定位

- 基于 **Spring AI 1.1** 的 RAG 平台 + Agent 编排平台，前代项目（15_ragent）的完整重写。
- 核心理念：**Spring AI = infra-ai**。ChatClient 替代 LLMService、ChatMemory/Advisor 体系按需取用；但两个刻意的"不用"：
  1. **向量读写手写 JDBC**（`VectorSearchChannel` / `IndexerNode` 直写 `spring_ai_store_vector`，cosine + HNSW）——为了集合过滤（`metadata->>'collection_id'`）、GUC 钉死索引路径（`SET hnsw.ef_search=200`、`enable_seqscan=off`）、批量嵌入控制（百炼 text-embedding-v3 批量上限 10）。
  2. **Rerank 无统一抽象**，自研 `RerankClient` 调百炼 qwen3-rerank。

### 1.2 四层十六模块（依赖方向是硬约束）

```
框架层   platform-agent-core        内核（com.agentframework 包根，刻意与业务包根分离）
基础层   platform-common            util / MyBatis 类型处理器 / 分页 DTO（最底层）
         platform-shared            配置属性 / Prompt 加载 / 模型装配 + 跨域 SPI 契约
能力域   platform-cache             CacheStore / Redis 装配 / 频率准入 / 断路器
         platform-observe           OpenObserve / Langfuse / TraceSampling
         platform-storage           S3(RustFS) 对象存储 + 文件元数据
         platform-knowledge         多通道检索 / 融合 / 重排 / 后处理 + RAG 生成
         platform-prompt            Prompt 资产 / 版本 / 能力包与绑定
         platform-ingestion         解析 / 分块 / 增强 / 富化 + 文档目录
业务层   platform-business          ★ 编排 + 双域：knowledge/（RAG 线）ops/（诊断线）
                                    orchestration/（ChatOrchestrator + 8 协作类）
                                    engine/（agent-core 桥接）/ workflow/common/
         platform-delivery          SSE 端口实现 + 事件协议 / 会话消息持久化 / 入口薄壳
         platform-mcp               MCP 外部工具来源
         platform-eval              评测：数据集 / 跑批 / 指标 / 对照 / 外部推送
收口     platform-console           控制台 / 全局异常 / AgentRegistry 端点
         platform-bootstrap         唯一可执行装配点（启动类 / yaml / sql / static）
```

**依赖方向（单向）**：bootstrap → 业务域 → 能力域 → shared → common。
**业务域之间不互相 import 实现**，跨域只走 shared 里的 SPI：

| SPI（定义在 platform-shared） | 作用 | 实现 |
|---|---|---|
| `DeliveryPort<S>` / `DeliveryPortFactory<S>` | 交付通道（事件、落库、流生命周期） | `delivery.sse.SseDeliveryPort` |
| `ConversationStore` | 会话事实（历史、先行对象） | `delivery.message.ChatMessageWriter` |
| `DocumentCatalog` | 文档目录（名称、原文链接） | `ingestion.catalog.IngestionDocumentCatalog` |
| `routing.RouteRegistry/RouteRule`、`intent.IntentResult`、`clarify.*` | 路由/意图/澄清契约 | business 侧实现 |

### 1.3 一次请求的调用链（先记这条链）

```
POST /chat/stream（ChatController，SseEmitter）
 └─ ChatService（入口薄壳）→ ChatOrchestrator<SseEmitter>.execute(...)
     ├─ 决策链：会话恢复 → 归一化 → 规则短路(traceId) → 显式范式 → 指代悬空
     │          → 意图分类 → 规则路由 → 分支
     ├─ ops 支路：AgentBranchDispatcher → OpsRunner（引擎跑 ops_diagnose_v2 图）
     │            → Outcome 分派（CLARIFY 追问 / DIRECT 直答 / ESCALATE 升级 / CANCELLED）
     └─ RAG 主线：ConversationStore.historyContext → KnowledgeRunner
         （引擎跑 knowledge_qa 图，检索 = extension tool）
         → 多通道检索 + 后处理链 → RagContextAssembler（上下文 + 引用映射）
         → KnowledgeAnswerService（流式生成）→ SseDeliveryPort
         → SseEventSender（message/trace/citations/clarify/meta 五事件）+ ChatMessageWriter
```

### 1.4 设计理念（本章可讲的"为什么"）

1. **模块按"框架/能力/业务"切而不是按技术层切**——能力域（knowledge/ingestion/prompt…）各自可独立演进甚至抽出，业务域只剩调度与交付；依赖方向由 ArchUnit 风格的测试固化（`ArchitectureFitnessTest`）。
2. **编排不碰传输**：ChatOrchestrator 泛型 `<S>` 对传输载体完全透明，内部不出现 SseEmitter/SseEventSender——换 WebSocket/WebFlux 只动 delivery 模块。
3. **交付不做决策**：platform-delivery 不判断意图/路由，只执行"怎么送到用户"。
4. **内核零业务**：com.agentframework 包根刻意与业务分离，内核可独立成仓发布。

---

## 2. 文档入库链路：从文件到可检索

### 2.1 链式引擎 + DB 配置驱动

**核心类**（platform-ingestion `com.jjx.customer.platform.ingestion.engine`）：
`IngestionEngine` / `IngestionNode`（接口：`getNodeType()` + `execute(IngestionContext, NodeConfig)`）/ `ConditionEvaluator` / `PipelineDefinition` / `NodeLog`。

- **节点注册**：构造器注入 `List<IngestionNode>`，按 `getNodeType()` 收集成 Map；DB 里 `sa_ingestion_pipeline_node.node_type` 字符串直接查 Map 匹配 bean——**DB 改配置即可调序/增删节点，不改代码**。
- **链式执行**：`execute()` = buildNodeConfigMap → validatePipeline（沿 nextNodeId 走链做**环检测**，`path.contains(current)` 即抛"流水线存在环"）→ findStartNode（入度 0 的节点）→ executeChain（`while nextNodeId != null`，`executedCount > maxNodes` 防死循环）。
- **ConditionEvaluator**：条件为 null 直过；文本走 **SpEL**（root=context，异常返 false）；对象型支持 `all/any/not/field` 组合；字段规则经 BeanWrapper 反射取值，operator 支持 `eq/ne/in/contains/regex/gt/gte/lt/lte/exists`。条件不满足返回 `NodeResult.skip`，仍写一条 success 日志（审计完整）。
- **默认流水线**：`PipelineBootstrap`（ApplicationRunner）DB 无 "default" 时引导 `fetcher → parser(MinerU content_list) → enhancer(CONTEXT_ENHANCE) → chunker → enricher(KEYWORDS) → indexer`。

### 2.2 六类节点（nodeType → bean）

| 节点 | 实现 | 要点 |
|---|---|---|
| fetcher | `FetcherNode` | 幂等（rawBytes 非空直接 skip）；按 SourceType 选 DocumentFetcher（HttpUrlFetcher） |
| parser | `ParserNode` | MIME 探测 + 规则校验；`DocumentParserSelector` 按 `supports()` 遍历取第一个命中，**无匹配直接 fail 不静默兜底** |
| enhancer | `EnhancerNode` | 文档级 LLM 增强（CONTEXT_ENHANCE 整理全文 / KEYWORDS / QUESTIONS / METADATA 四任务）；`rag.ingestion.enhancer-enabled` 默认 **false 空跑** |
| chunker | `ChunkerNode` | 详见 2.3；FixedSize 策略被自动改写为 STRUCTURE_AWARE（warn） |
| enricher | `EnricherNode` | chunk 级富集（KEYWORDS/SUMMARY/METADATA）；**虚拟线程 + Semaphore(5)** 按块并行，`CompletableFuture.allOf().orTimeout(300s)` 防 HTTP 挂起永久阻塞 |
| indexer | `IndexerNode` | 手写 JDBC 直写 pgvector，详见 2.4 |

**解析器四实现**（@Order 决定优先级，Tika `LOWEST_PRECEDENCE` 兜底）：
- `MarkdownDocumentParser`：commonmark-java + GFM tables，产出 Heading/Paragraph/Code/List/Table 五种 Block。
- `CsvDocumentParser`：Tika AutoDetectReader 字符集探测（UTF-8/GBK/UTF-16）+ 剥 BOM，RFC4180 手写解析（引号包裹/字段内逗号换行/`""` 转义），整个 CSV 产单个 TableBlock。
- `MinerUDocumentParser`（最高优先级，需 API key + pdf/word/ppt MIME）：Redisson `RPermitExpirableSemaphore` **跨实例限流**（并发 5，tryAcquire 30s 等待、900s 租期）→ 申请上传链接 → PUT 字节到 MinerU OSS → 轮询（5s 间隔，300s+30s 超时）→ 下载 zip → 解包。
  - **解包双路线**：`content_list`（新路线）：text（text_level 1-6→标题块）/code（约 50 语言白名单）/image（图片上 RustFS，caption 保留）/table（**Jsoup 解 HTML + expandMergedCells 展开 rowspan/colspan 合并单元格**，每行自包含；失败降级段落块保 HTML 原文）。`markdown`（旧路线兜底，表格丢结构）。
  - **跨页代码合并**：`bracketStack` 栈扫描 `()[]{}` 返回 [depth, dangling]——前段 depth>0（开了没闭）且后段 dangling>0（闭了没开）才合并，比净计数能区分两种未配对形态。
- `TikaDocumentParser`：`tika-max-chars=1000000`（单参版默认 10 万会**静默截断**，这是个坑）。

### 2.3 分块：Block-Aware 主路线（面试重点）

统一入口 `StructuredChunkingService.chunk()`——单一真相源，四分支：
PRE_CHUNKED（1:1 映射）→ 整篇单块哨兵（chunkSize=-1）→ **blocks 非空走 Block-Aware** → 空则 legacy StructureAwareTextChunker。

**Block-Aware 流水**：
```
BlockSanitizer 清洗 → overlapForLanguage 分语言 → HeadingHandler 维护章节栈
→ 六种 BlockChunker 分发（Heading/Paragraph/Table/Image/Code/List）
→ ChunkPacker 装箱 → ChunkJunkFilter 过滤残片
```

关键设计（每条都有真实理由）：

1. **表格双文本策略**：`content` = 完整 markdown 表格（**每块带全表头**、cell 内换行→`<br>`、`|` 转义防截断）；`embeddingText` = `sectionContext(sheet/caption/headers) + 每行"列名: 值"` key-value 串——**key-value 只算 embedding 不落 content 不存 metadata**（上下文紧凑、展示完整）。
2. **ChunkPacker 预算**：target 1400 / min 600 / max 1800 字符；MERGEABLE = {PARAGRAPH, LIST, IMAGE, HEADING}，CODE/TABLE 原子；"说明+代码"≤1800 并并同块；**章节边界**：跨章节且 buffer≥min 才 flush（不足 600 忍一次跨章节防碎块）。
3. **overlap 分语言**：采样前 4000 字符，CJK 占比 ≥0.10 → overlap 100 字符，否则 200——中文信息密度高（同字符数 token 约英文 4-5 倍），重叠预算减半。
4. **BlockSanitizer（宁漏杀不误杀）**：U+FFFD 乱码删除；相邻重复标题去重；导航行三条件（<80 字符 + 无句末标点 + 命中"返回../"特征）。
5. **ChunkJunkFilter**：纯符号碎块（<100 字符且字母数字占比 <0.3，如 MinerU 页码水印）；无 CJK 短残片（<50 字符）丢弃；**中文短块不按长度丢**（17 个汉字可承载完整业务规则）。
6. **BoundaryAwareSplitter**（最底层原子能力，ParagraphChunker 与 legacy 共用）：6 级边界递归降级 ①空行 ②单换行 ③中文句末标点 ④英文句末（须后接空白，防切 URL）⑤分号 ⑥逗号 → 字符硬切兜底；永不抛异常永不死循环。

### 2.4 Indexer：手写 JDBC + 小块检索大块生成（入库侧）

- **嵌入批量 = 10**（百炼 text-embedding-v3 上限，超限 400；Spring AI 默认 BatchingStrategy 一次全发会挂）；整批失败回退逐块，单块失败跳过。
- **INSERT 批量 = 50**（multi-row VALUES + `?::vector`），单批失败跳本批，**成功数 >0 即节点成功**。
- metadata 构造：`doc_id`（**必须用 docId 不能用 taskId**——曾写 taskId 导致评测 expected_doc_ids 全 0 的教训）、collection_id、chunk_index、block_type、outline_path、白名单透传字段（默认 keywords）。
- **小块大块**（`rag.ingestion.child-chunk.enabled=true`）：父块按 400/500/120/60（target/max/min/overlap）切子块进向量表；子块 metadata 带 `parent_key = doc_id:chunk_index`；**父块原文存 `sa_chunk_parent`**（`ON CONFLICT DO NOTHING` 幂等）。单块切不出多片不展开（小父块本身合格）。

### 2.5 幂等、MQ、docver（工程三件套）

- **三层幂等**：① 引擎开头 `DELETE FROM spring_ai_store_vector WHERE metadata->>'doc_id'=?` + 同 doc 的 sa_chunk_parent 残留清理；② MQ 消费 `CAS 抢占`（`UPDATE ... SET status='PROCESSING' WHERE task_id=? AND status NOT IN ('PROCESSING','DONE')`，claimed=0 跳过——防 at-least-once 重投并发跑两遍）；③ docId 已有 sa_document 记录直接回 DONE。
- **MQ 双实现**：`rag.mq.type` 切 rocketmq（默认，另有事务消息 half→本地事务→commit/rollback+回查）/ redis（Stream + 消费组，consumer 名=hostname-pid 防多实例互抢，毒消息也 ACK 防死循环）。消费端 `Semaphore(5)` 限并发对齐 MinerU 信号量与 DB 池。
- **docver 版本化失效**：入库落库后（**FAILED 也 bump**——保守失效，宁可多查不可脏读）`DocumentVersionStamp.bump(collectionId)` 双 INCR：全库哨兵键 `_all` + collection 键。缓存 key 拼版本号，**旧 key 不删、等 TTL 漂走**（不做 KEYS/SCAN 扫删——Redis 反模式）。Redis 不可用返回 -1，调用方整体旁路。

### 2.6 存储：RustFS（S3 兼容）与 VLM

- 双桶语义：kb 桶（私有文档）+ assets 桶（图片等公共资产，公共读）。
- **`streamPut` 用预签名 URL + HttpURLConnection 流式上传**——AWS SDK v2 同步/异步上传都会在 SigV4 签名管线把 payload 缓冲进堆内存，预签名（UNSIGNED-PAYLOAD）绕开。
- VLM（qwen-vl-max）：图片描述**双文本策略**——description 进 embeddingText（可检索）、原图 markdown 留 content（可展示）；虚拟线程 + Semaphore(5) **并行预生成**所有图片描述（此前串行 20 张图拖数分钟）。

---

## 3. RAG 查询链路：从提问到带引用的回答

### 3.1 查询理解（改写策略是实测驱动）

- **QueryNormalizer**（纯函数）：全角→半角 → 压空白 → 循环去无意义前缀（"请问一下/麻烦问下"按长度降序可叠加）→ 去首尾标点。**只清洗不改语义**（语义改写归 LLM）。
- **QueryRewriter**（LLM）：`rewrite(query, historyContext, clarification)`。三条实测策略：
  1. **AUTO 档首轮自包含问题原样透传**——LLM 重述会换掉 BM25 赖以命中的字面词，首轮改写是净负优化；只有带历史/补充才消解指代。
  2. **英文不走中文语义改写**（实测 recall@5 -3.5pp），改走保守 spellFix（只修错拼不换表述，且不受 RewritePolicy 约束——noisy query 在 OFF 档也要纠错）。
  3. 失败/空/与原文相同 → 原样返回（改写永远不比不改差）。
- **IntentClassifier**：LLM 分类输出 `{domain, confidence, needs_web_search, reason}`；**temperature 0.0 按调用点覆盖**（共享 bean 默认温度非零，实测同问题 4 次判出 2 次 knowledge/2 次 chitchat）；域清单动态拼装（RouteRegistry.domainDescriptors()，workflow 域自动进分类器视野）；带最近 600 字历史尾段（标注"仅供判断话题延续"）；异常兜底 knowledge/0.5。
- **多轮历史 HistorySpec 两段查询**：`HistorySpec(beforeMessageId, rounds=3, totalChars=4000)`。**按"第 N 条 user 消息"定界**而非 `LIMIT 轮数×2`——半轮对话会让条数法多带悬空消息。第 1 段 SQL 只取 user 消息 id（倒序 LIMIT rounds），floorId=最早那条；第 2 段取 [floorId, beforeMessageId) 区间全部消息升序。渲染按总字符预算从最新往回装，只有最新一条允许截断（带标注）。

### 3.2 多通道检索与后处理链

**通道接口** `SearchChannel`：`getName/getType/isEnabled/search(SearchContext)`；Spring 构造器注入自动聚合进 `MultiChannelRetrievalEngine`。

- **VectorSearchChannel**（手写 JDBC）：`SELECT content, metadata, 1-(embedding <=> ?::vector) AS similarity ... ORDER BY embedding <=> ?::vector LIMIT ?`；同连接先 `SET hnsw.ef_search=200 / hnsw.iterative_scan='relaxed_order' / enable_seqscan=off` **钉死 HNSW 索引路径**（实测：语料 2 万+子块后 ef=200 的代价估算超过 Seq Scan，规划器翻转到全表余弦），finally RESET 三 GUC 防连接池会话污染。阈值过滤放 Java 侧（不破坏排序）。
- **Bm25KeywordSearchChannel**（生产实配）：ParadeDB pg_search（Tantivy 内核），`content @@@ ?` + `pdb.score(content)`；启动期校验扩展缺失自动建 bm25 索引。备选 sql 模式（JSONB keywords 全表扫，量级大后要换）。
- **通道隔离**：每通道 `CompletableFuture.supplyAsync(ragContextExecutor).orTimeout(channelTimeoutMs=5000)`——单通道异常/超时返回空结果不炸整体；`ragContextExecutor` 固定线程池（core=max=8，queue=16，CallerRuns，**ContextPropagatingTaskDecorator 跨线程传播 MDC+OTel**）既是并发执行器又是**天然 bulkhead**。
- **SearchContext 全参数**（@Builder）：query/rewrittenQuery/topK/threshold/budget(recallBudget=60, candidateLimit=40, contextTopK=10)/collectionId/restrictedDocIds/metadata——**检索缓存 key 硬约束含全部参数**，eval 参数扫描的正确性基础。

**后处理责任链**（order 固定）：

```
sibling-expansion(0,禁用) → dedup(1) → doc-scope(2,禁用) → ticket-type(3,禁用)
→ fusion(5, RRF) → rerank(10) → parent-aggregation(11)
```

- **Dedup**：`ChunkIdentity = doc_id#内容SHA-256`——**必须带 doc_id**：LiveRAG 语料同一篇文章挂多个 doc_id，纯内容哈希会把期望文档与孪生副本合并掉（评测按 doc_id 打分）。
- **RRF Fusion**：`score = Σ w/(rrfK + rank)`（生产 rrf-k=20）；通道权重 VECTOR 1.0 / KEYWORD 0.8 / GRAPH 0.5 / WEB 0.4；融合后按 candidateLimit 截断；单通道跳过融合。
- **Rerank（qwen3-rerank）三个实战点**：
  1. **分数随请求总长衰减**：实测同一文档 12k 字符 relevance_score=0.349，55k 掉到 0.208——109 候选一次喂 47k，期望文档 0.2822 被 0.3 下限整批误杀。**对策：候选 > batch-size(20) 分批调、跨批按分合并取 topN**（分批后期望文档 0.3857 过阈、60 条过阈）。
  2. **端点双段 URL 陷阱**：官方新端点要求 `.../text-rerank/text-rerank` 双段，@PostConstruct 缺哪段补哪段（旧单段配置 400 静默退化）。
  3. **子块超采 ×3**：候选含 parent_key 子块时 topK×3（同父折叠吃席位，不超采则父块数 < topK）；失败 fallback = 候选前 topN 拷贝。
- **ParentAggregation（小块检索大块生成的检索侧出口）**：带 parent_key 的子块按父分组，每父一个代表块——内容 = 批量查 `sa_chunk_parent` 原文（查不到**回退子块最优内容，绝不丢命中**）、分数 = 子块最高 rerank 分、metadata 带 `parent:true + child_hits + child_scores`。**为什么放 rerank 之后**：打分发生在子块级（语义集中、分数区分度高，对照实验期望内容平均 +0.103），交叉编码器吃子块短文本；聚合只是折叠席位换回父块原文。多子命中同父只占一个席位 = 同文档挤占的天然解。存量无 parent_key 语料零行为变化（自门控灰度）。

### 3.3 生成与引用溯源

- **RagContextAssembler**：按 doc_id 分组（LinkedHashMap 保首现序=相关度序），组内按 chunk_index 还原阅读序，一个 `<content ref="N">` = 一份文档全部命中块拼接。**刻意不注入文档名与分数**（会诱导"出自《XX》"和偏向高分块）。产出 `RagContext(text, docCount, citations)`，Citation 含 ref/docId/docName/chunkCount/preview/sourceLocation（经 DocumentCatalog SPI 批查，失败不影响主链路）。
- **citations 先行**：流式开始前 `emitCitations` 先下发，前端先渲染角标面板再收正文。
- **KnowledgeAnswerService**（独立 bean——流式方法的 span 生命周期要覆盖整个 Flux 订阅期，AOP 需装饰返回值而 Spring AOP 只切跨 bean 调用）：user message 结构 = `contextText + <conversation-history> + <question>...</question> + <output-requirement>引用角标指令`。**引用指令紧贴生成位置**是实测结论：只在 system 里写时模型只标末尾一处。ragChatClient 不挂 ChatMemory，多轮靠显式拼 history。
- **prompt**（rag-answer-kb.md）要点：信息边界最高约束（严禁外部知识）、角标逐段贴尾、多资料连写 `[1](#cite-1)[3](#cite-3)`、历史只用于理解指代不是资料、代码/JSON 原样完整展示。
- **两段式**：引擎只编排检索（knowledge_qa 图），生成在引擎外（KnowledgeAnswerService ChatClient 流式）——检索进图获得可观测/可 replan，生成留在外面获得流式与遥测注解的成熟管线。

### 3.4 一次 knowledge 轴查询的完整数据流（背下来）

```
ChatOrchestrator.execute
 0 落库本轮消息 + ResumeCoordinator 恢复检查 + QueryNormalizer
 1 RouteEvaluator 第一次求值（traceId 正则短路，零 LLM）
 1.5 agentChoice=ops 显式范式直进 ops
 2 指代悬空检测（"这篇文档"无先行对象→反问）
 4 history 两段查询；cacheable = history 为空（带历史答案缓存两层旁路）
 5 AnswerCacheCoordinator：exact → 语义（pgvector ≥0.95）
 7 KnowledgeRunner：ephemeral 引擎会话跑 knowledge_qa 图
    kb_normalize → kb_classify(温度0) → kb_route(第二次规则求值) → kb_route_gate
    → kb_rewrite(RewritePolicy) → kb_retrieve(TOOL节点=retrieve_knowledge) → kb_critique
    （反思环：不足回 kb_rewrite，上限 3 轮；跨轮累积槽 kb_chunks_all）
 10 RagAnswerStreamer：DegradeGate → 组装 RagContext → emitCitations → 流式生成
    → 完成回调（虚拟线程）：补轨迹步 → persistAnswer(citations) → emitTrace 完整版 → 缓存写回
```

---

## 4. Agent 内核深潜（platform-agent-core）——面试核心章节

> 口径校准：CLAUDE.md 等旧文档中的 `ToolLoopEngine` / `StageSpec` / `LoopExitChecks` /
> `JsonToolCallAdapter` / `internalToolExecutionEnabled` / `tool-call-mode` 均属旧业务层内核，**当前代码已不存在**。
> 2026-09-18 起内核整包替换为图范式内核（com.agentframework 包根）。面试时按本章口径讲。

### 4.1 为什么自研内核（设计理念先行）

1. **要挂起-恢复**：诊断 Agent 必须支持"问用户一句、等半天、回来继续跑"。LangGraph/LangChain 系没有原生的跨进程检查点 + 槽位快照 + 确定性恢复；Spring AI 没有 workflow 概念。游标驱动的节点主循环 + 逐节点 checkpoint 是最直接的解。
2. **要声明式图而不是硬编码循环**：诊断流程是"三阶段 × 每阶段 think→act→decide 循环 × 阶段间 replan"，图（节点/条件边/Region）让流程成为**可声明的数据**，骨架零业务，业务图由工厂产出。
3. **要三层横切分离**：拦截器（怎么执行）、过滤器（数据变成什么样）、守卫（能不能做）——职责正交，配错一处不殃及另一处。
4. **要零业务依赖可独立成仓**：com.agentframework 包根与业务包根刻意分离，分层边界由测试守护。

### 4.2 模块结构（七包）

| 包 | 职责 | 关键类 |
|---|---|---|
| `engine.core` | 引擎入口与运行契约 | `Engine`/`DefaultEngine`/`EngineBuilder`/`EngineConfig`/`NodeContext`/`NodeResult`/`RunResult`/`RunOutcome` |
| `engine.workflowruntime` | **游标驱动节点主循环** | `DefaultWorkflowRuntime`（约千行，同时实现 WorkflowRuntime 与 BranchInvoker）+ `NodeExecutorRegistry`（按 NodeType/按名字双索引）+ 7 个执行器：Llm/Tool/Human/Condition/Parallel/SubWorkflow/Custom |
| `engine.contextmanager` | 内存态 + 持久化委托 | `DefaultContextManager`（sessions/workspaces/slots 三个 ConcurrentHashMap + ephemeral 集合） |
| `engine.middleware` | 横切统一管道 | `MiddlewarePipeline`/`GuardPipeline`/`FilterPipeline`/`InterceptorPipeline`/`CacheMiddleware`/`TraceMiddleware` |
| `engine.toolexecutor` | 工具面 | `Tool`/`ToolProvider`/`ToolRegistry`/`DefaultToolExecutor`（详见第 6 章） |
| `engine.*` 其余 | agentmanager/promptmanager/persistence/scheduling/policy/view/definition/pluginruntime | `PersistenceManager`/`RecoveryManager`/`PolicyResolver` |
| `definition.*` | 声明式模型（纯数据） | agent/node/workflow/region/tool/prompt/policy/codec 子包 |
| `crosscutting.*` | interceptor / filter / guard / cache / metrics / trace | 见 4.4-4.6 |
| `runtime.*` | session（Session/Cursor）/ slot（Slots/SlotValue/SlotSnapshot）/ workspace / event（EventBus）/ persistence（Store 接口） | |
| `infra.*` | modelgateway（ModelGateway/ModelProvider）/ storage（InMemory/File）/ messagebus / vector | |
| `extension.*` | 插件注册 / 权限（Quota/PermissionChecker）/ 沙箱 / SPI | |
| `sdk` | 门面 DSL | `AgentFramework.builder()`/`Agents`/`Workflows` |

**执行主链**（DefaultEngine.execute 的 javadoc）：

```
守卫(BEFORE_AGENT) → 输入过滤(BEFORE_AGENT_INPUT) → 拦截链(AROUND_AGENT)
→ DefaultWorkflowRuntime.run（节点主循环）
→ 输出过滤(BEFORE_OUTPUT) → 守卫(BEFORE_OUTPUT) → 状态落库 → 事件/追踪导出
```

`EngineConfig` 默认：maxSteps=100、nodeTimeout=60s、trace/cache/metrics/persist 全开。

### 4.3 Interceptor / Filter / Guard 三兄弟（必考，务必分清）

| 维度 | Interceptor 拦截器 | Filter 过滤器 | Guard 守卫 |
|---|---|---|---|
| 回答的问题 | **怎么执行**（流程控制） | **数据变成什么样**（数据变换） | **能不能做**（准入决策） |
| 数学形态 | 环绕：`intercept(Invocation::proceed)` 可选择是否继续 | 管道：`filter(input)` 串行直通变换 | 谓词：`check(ctx)` 返回决策 |
| 接口 | `<T> T intercept(Invocation<T>, InterceptorContext)` | `O filter(I input, FilterContext)` | `GuardDecision check(GuardContext)` |
| 挂载点（枚举） | AROUND_AGENT / AROUND_WORKFLOW / AROUND_NODE / AROUND_PROMPT / AROUND_LLM / AROUND_TOOL | BEFORE_AGENT_INPUT / PROMPT / BEFORE_LLM / AFTER_LLM / BEFORE_TOOL_CALL / AFTER_TOOL_RESULT / AFTER_NODE_OUTPUT / BEFORE_OUTPUT / AFTER_OUTPUT | **14 个**：BEFORE/AFTER × AGENT/WORKFLOW/NODE/PROMPT/LLM/TOOL/OUTPUT + ON_ERROR |
| 内置实现 | Retry(5,指数退避) → Trace(10) → Timeout(15,虚拟线程+future.get) → Metrics(20) → CircuitBreaker(25) → Cache(30) → Logging(1000) | Format(5) → Pii(10,正则掩码) → ContextWindow(20,保SYSTEM+最近N条) → Dedup(30) → Length(40) | MaxIterations(1) / CostBudget(2) / ToolPermission(5) / Content(10) / Rbac(20) / OutputCompliance(30) |
| 短路行为 | 不调 proceed() 即中断 | 无短路（只改数据） | **Deny/AskApproval/BreakLoop 短路；Transform 叠加改写载荷** |
| 典型用途 | 重试/超时/熔断/缓存/追踪 | 脱敏/截断/窗口压缩 | 预算/权限/迭代上限/内容合规 |

**GuardDecision 是 sealed interface 五变体**（设计亮点）：`Allow / Deny / Transform（改载荷后放行）/ AskApproval（转人工审批）/ BreakLoop（中断循环）`。
- Deny/AskApproval → 抛 `GuardDeniedException` → 引擎转 FAILED；
- BreakLoop → 抛 `LoopBreakException` → 引擎 completeEarly **优雅收尾**（不是失败），运行循环跳过回边只走 Region 出路。

**循环治理**（旧 LoopExitChecks 的现役等价物）= 内置守卫组合：
- `Guards.MaxIterations`：`used >= maxIterations → breakLoop("max_iterations_reached")`，经 `EngineBuilder.loopGuard(name, max)` 注册、节点 `NodeMeta.withGuards(name)` 引用；
- `Guards.CostBudget`：Region token 配额（QuotaEnforcer.usage）超限 breakLoop。

**InterceptorChain 折叠机制**：`of(List)` 按 supports() 过滤、order() 排序，从尾部向前逐层包裹 `() -> interceptor.intercept(next, ctx)` 成嵌套调用——经典洋葱模型。`InterceptorAttributes` 定义标准属性键（cache.policy/retry.policy/timeout.policy/circuit.key/trace.span.* 等），策略经 `InterceptorPolicy + PolicyBinding` 允许 Region/Agent 作用域按名字引用拦截器子集。

### 4.4 节点主循环（内核心脏，逐帧讲）

`DefaultWorkflowRuntime.run(WorkflowExecution)`：

```
cursor = entryNode；checkpoint.save（初始）
while true:
    nodeId 为空 → RunOutcome(COMPLETED) ✅
    visited ≥ maxSteps 或 state==CANCELLED → FAILED/CANCELLED ❌
    invokeNode:
        BEFORE 守卫 → span → AROUND_NODE 拦截链 → 执行器
        → AFTER_NODE_OUTPUT 过滤 → AFTER 守卫
    LoopBreakException? → 标记 loopBreak，之后只许走出 Region
    合并 slotWrites（Region 短名 → {prefix}_{name} 展开）+ messages + usage
    收敛观测（sufficiency delta < minDelta → 收敛出口）
    NodeResult.suspended? → checkpoint.save → RunOutcome(SUSPENDED) ⏸ 不推进游标
    resolveNext 路由（三级优先）:
        ① 动态边（白名单 DynamicPolicy.allows 校验，拒绝发 ROUTE_REJECTED 事件回退静态边）
        ② 显式 nextNodeId（NodeResult.dynamic 携带）
        ③ 静态出边（priority + 条件表达式 + 边 guard）
    cursor.advanceTo(next).arriveVia(edge) → checkpoint.save（两表 UPSERT）
```

三个设计点：
1. **逐节点 checkpoint**：每推进一步持久化一次（ops_engine_session + ops_engine_slots），挂起/崩溃后恢复到**节点粒度**；恢复时**游标不推进、重入同一节点**（HumanNodeExecutor：有输入则完成、无输入再挂起——幂等重入）。
2. **条件表达式**：NodeContext.variables() 构造变量表 `{slots, input, sessionId, nodeId}`；Region 带 slotPrefix 时物理槽 `{p}_{name}` 以短名 `name` 暴露（regionScopedSlots）——图声明用短名，存储用全名。
3. **表达式 null 陷阱**（真实踩坑）：`slots.x > 0` 对未设置槽位为真（字符串比较兜底）——分支判定一律用 `== 1` 这类等值比较。

### 4.5 Context：内核没有大而全的 AgentContext，状态拆四层

| 层 | 类 | 内容 |
|---|---|---|
| 节点执行上下文 | `NodeContext`（record） | 执行器可读的全部运行态：agent/session/workflow/cursor/slots/workspace/input/trace/parentSpan/branchInvoker/attributes |
| 槽位容器 | `Slots` | **工作流读写状态的唯一出口**；内部 Map 同步块保护；put 递增 per-slot version（乐观并发）；`snapshot(sessionId)` 产出 SlotSnapshot（跨进程恢复的真正"快照"） |
| 追踪上下文 | `TraceContext`（record） | traceId + rootSpan |
| 跨线程传播 | `TaskPropagation`（静态钩子） | 内核不依赖 OTel；宿主装配期注入 `ContextPropagator::wrap`——**Timeout 拦截器把节点 submit 到新虚拟线程，线程本地不继承，不搬运则 span 退化成根 trace**（一次问答断成数截） |

三层职责解读：**Cursor 管"走到哪"、Slots 管"知道什么"、Session 管"整体状态"**——不搞一个巨型 God Object。

### 4.6 Slot：从声明到来源台账

**内核侧（机制）**：
- `SlotSpec(name, type, required, defaultValue, scope)` + `SlotsSchema`（applyDefaults + validate）+ `SlotPolicy`（STRICT：未知槽引用报错 / OPEN：警告）。
- `WorkflowBuilder.regionSlot(name, type)` 声明 Region 局部槽模板；运行时 `expandRegionSlotWrites` 按 slotPrefix 展开物理槽——**阶段之间 scratchpad 互不污染**（inv_scratchpad / res_scratchpad / ver_scratchpad）。
- `SlotValue(name, scope, scopeKey, value, version, persistent, updatedAt)`；ephemeral 会话的槽不落库（REST 单轮诊断双处拦截：persist 与逐节点 checkpoint）。

**业务侧（ops 域，机制的用法示范）**：
- `OpsSlotCatalog` **声明式目录**：`record Spec(name, required, question, hint, normalizer, extractHint, options)` + `.withAuto(defaultValue, inferable, resolvable)`。八个槽：environment/interface/time/error/payload/response/symptoms/trace_id；normalizer 是 UnaryOperator（"正式/生产/线上"→prod）——**目录显式传参，无隐式默认**，自主性边界由目录声明。
- **SlotProvenance 来源状态机**：`rule → default → llm → log_query → user_override（终态）`——前四机器取值，用户推翻转 user_override。存储沿用 `inferred_slots` 槽的 JSON 数组 `[{slot, value, method, evidence}]`（同槽覆盖只留最新）。
- **SlotQuestion / ClarifyRequest**（shared 交付契约）：`SlotQuestion(slot, question, hint, required, options, value, provenance, evidence)`——**value 非空 = 机器已补全待确认**，前端渲染"值 + 来源角标 + 改成…"；`ClarifyRequest.isDecision()` 区分 CLARIFY 卡（问信息）与 DECIDE 卡（决策移交）。

### 4.7 Session：引擎会话 / 任务 / 对话四层表结构（讲清这张图就赢了）

| 表 | 归属 | 生命周期 | 内容 |
|---|---|---|---|
| `sa_conversation` / `sa_message` | delivery | 对话 | 消息 + citations + clarify 卡片 JSON（刷新页面可重渲染卡片） |
| `sa_agent_task` | business | **任务（工作边界）** | status(open/running/suspended/concluded/closed/abandoned)、stage、slots、autonomy_level、chainTraceId、attempt_count；2026-09-19 **取代 sa_agent_session**（旧表把 Task 级与 Attempt 级两个生命周期塞在一起且 conversation_id UNIQUE） |
| `ops_engine_session` | engine | **执行（引擎游标）** | session_id PK + record JSONB（整份 SessionRecord 含 cursor 与 messages） |
| `ops_engine_slots` | engine | 执行 | snapshot JSONB（SlotSnapshot，恢复不问已填项） |

- 内核 `Session` 接口：id/tenantId/agentId/workflowId/state/cursor/messages/traceId...；`SessionState`：CREATED/RUNNING/**SUSPENDED**/COMPLETED/FAILED/CANCELLED（isTerminal）。
- `Cursor(nodeId, step, lastEdge, state)` = 持久化断点，循环计数存 state 的 LOOP_COUNTERS。
- **一个 attempt 一个引擎会话**：`attemptIdOf(taskId, n) = "ops-" + taskId + "#" + n`（替代旧 "ops-"+conversationId 覆盖式 id）；attempt≥2 预填 `intake_confirmed=1` 跳过重复确认门。
- **恢复链路**：ChatOrchestrator 第 0 步 `ResumeCoordinator.findResumable` → SUSPENDED 恒归任务 / CONCLUDED 默认归（除非 targetShifted：新 traceId/接口路径 ≠ 已确认值）→ `requireResumeTarget = task.agentId()`（空白回 ops 兼容存量）→ OpsRunner：`contexts().load(attemptId)` + `taskService.claim()` **抢占防并发** → `engine.resume`（用户答复写 `user_clarify` 槽）。引擎侧无记录（旧版本存量）→ 带已确认槽位重开新 attempt 自然兜底。
- **回收**：`TaskReaper`（@Scheduled 60min）三件事——abandonStale（TTL 过期任务置 ABANDONED）、回收终态任务的引擎两表（engine-retention-hours ≤0 关闭只统计）、孤儿会话回收。实测教训：存储大户是 ops_engine_slots（曾 4141 个孤儿会话 76MB），不是 sa_message。
- **PgEngineSessionStore 读写失败抛 IllegalStateException 而非静默 empty**——empty 会被当"无会话"重开新诊断。

### 4.8 上下文分层架构：Conversation → Topic → Task → Attempt（业务侧设计，面试大杀器）

> 内核的 Context 四件套（4.5）管"单次执行内的状态"；本节管**跨轮次、跨天的上下文如何组织**。
> 权威设计：docs/plans/context-and-core.md（「上下文与会话生命周期设计」）。

**问题诊断（先讲病根）**：系统原先用**一个"会话"概念同时承载三件事**——对话容器（conversation）、一次执行（attempt）、挂起状态（suspend state），共用 `"ops-"+conversationId` 一个标识。于是四个模块各自解释"会话结束了没"：引擎看 Session 状态 COMPLETED（→ 重建，上下文全丢）、业务看 TTL 60 分钟（→ 还能恢复）、预算挂在引擎会话上（"收尾归零"只是重建的副作用）、上下文跟着引擎走（重建即丢）。**四套答案，且没有任何一处显式声明过"一次诊断"的边界在哪**——所有错位不是各自写错了，是同一件事被四个地方各解释了一遍。

**四层结构：每层只回答一个问题**

```
Conversation   记忆的封闭域    「什么可以被一起记住」   ← 硬边界：不跨会话
  └─ Topic     相关性的边界    「什么应该一起进上下文」
       └─ Task 工作的边界      「什么算解决了」
            └─ Attempt 执行的边界「一次能烧多少预算」
```

- 四层**互不重叠**；之前所有错位，根源都是有人试图用一层的边界回答另一层的问题。
- **Task 是通用层不是可选层**：RAG 的"查一下 invoiceCode 取值规范"也是一个 Task（attempt 恒 1、不允许挂起、产出=Answer）——结构统一，策略可声明。把 Task 做成 RAG 可选的，等于把"四套答案"的病根重新种回去。
- 与存储对应：Conversation→sa_conversation/sa_message；Topic→（暂缓，见下）；Task→sa_agent_task；Attempt→ops_engine_session/slots（id = `ops-<taskId>#<attemptNo>`，attempt 间不互相覆盖）。
- **收尾落 CONCLUDED 而非 DONE**——"出结论"≠"目标达成"，下一轮默认沿用（SUSPENDED/CONCLUDED 都归任务，例外=换目标强信号 targetShifted）。

**核心命题：上下文是计算出来的，不是存出来的**

```
真相源（不可变，append-only）→ 状态（可查询）→ 投影（纯函数·有版本）→ 组装（预算·保真）→ 上下文
                                                                ↓
                                                           组装记录（审计）
```

三条不变式（本节所有设计由它们推出）：
1. **计算而非存储**：上下文是运行时视图，不持久化——一致性问题**不存在**（不是"被解决了"）；异步物化是在给它制造问题。
2. **有界而非全量**：组装成本 = O(关联数据量) ≠ O(会话历史量)；必须有 ORDER BY…LIMIT，不能靠"数据恰好不多"。
3. **留痕而非覆盖**：真相源只增不改，视图可重建——审计链完整、schema 变更可重投影。

**组装公式**（什么进上下文）：

```
context(新 attempt) = user.preferences + topic.summary(结构化导航)
                    + task.findings[active] + linkedTopics[*].summary(仅 confirmed 边)
                    + attempt.scratchpad
✗ 其他会话的内容（永不）  ✗ 其他主题的原始消息（永不）  ✗ 未确认的隐式关联（永不）
```

- **Topic 是默认相关性单位**；跨主题必须**显式建边**（TopicEdge：related/supersedes/depends-on × proposed/confirmed/rejected × 规则/模型提议/用户三来源），不做隐式相似度召回——不可解释、会漂移、审计说不清；**只有 confirmed 边参与组装**。
- **永远不带原始消息**：sa_message 是 ground truth，不是上下文。三层职责：导航层（topic.summary，结构化 JSON：有哪些 Task/结论/未决假设/实体）默认注入；证据层（finding.evidence 指针+摘录）默认注入；**原始证据按需解引用**。
- 因为 summary 是派生投影而非存储的散文，"把 Task 从 Topic A 移到 B"是廉价操作（重新投影即可）。

**投影五条约束**（工程上怎么保证"算得对"）：
1. **保真等级**：verbatim（用户原始报文/日志摘录，禁改写——被改一字节的报文比缺失更危险）/ structured（traceId、错误码）/ summarized（已关闭 task 结论）。verbatim 只能整体取舍不能压缩。
2. **确定性**：同状态必算出同上下文——ORDER BY 全序、显式 as-of 时间戳、组装期快照隔离。
3. **纯计算**：投影中绝不能含 LLM 调用；需要 LLM 的压缩必须在组装路径之外。
4. **失败可区分**：投影失败 ≠ 投影为空；组装器遇投影失败必须响亮失败，不得产出"降级上下文"。
5. **版本治理**：投影规则版本应进 AgentFingerprint（现在只覆盖 prompt hash，是本设计对代码的唯一必改点）。

**上下文源二分：机制统一，治理分离**——投影源（f(状态)→内容，变更由数据结构驱动，版本跟 schema）与指令源（prompt 资产，变更由调优驱动）**共用同一组装器、同一组装记录，但版本独立治理**（合并版本号会让无关变更互相牵连）。现状证据：`OpsPrompts.compose(body, tools, scratchpadSlot)` 已是这个形状——同一方法拼"资产正文"（指令）与"从 stage 定义派生的工具行"（投影）。**三个组装时点**：装配期（一次，资产正文+结构派生段）/ attempt 期（每 attempt 一次，findings/topic 投影）/ 调用期（每轮 LLM 调用，`{{slots.*}}` 插值）。每次组装落一条**组装记录**（included/skipped/version/tokens）——同时服务审计、Topic 切分调优、预算调参。保留**被治理的逃生舱**（显式直通通道但必须标记来源）——若加一点上下文都要建表写规则，团队早晚会绕过它，绕过之后审计链就断了。

**Topic 的定义：相关性，不是领域实体**（必须钉死否则组装自相矛盾）："下单超时→根因在订单、触发在库存、关联配置"——按相关性算**一个** topic；按领域实体算三个（会把多服务根因诊断恰在最需要上下文连续时切断）。跨领域现象由 Finding 上的 entities 标签表达，不靠切分 Topic。Task 按**目标**切（同主题内"为什么失败/正确报文/怎么避免"= 三个 Task 共享一个 Topic，上下文天然连续）；**失败不关 Task 只关 attempt**（Task 回 open——"查不出来"正是需要再试一次的情形）。

**预算分层与跨会话沉淀**：
- Conversation.budget → Topic.budget（软上限，触发摘要压缩）→ Task.budget（耗尽挂起等用户决策）→ Attempt.budget（maxLlmCalls=24 / 300s）。耗尽策略 = 挂起+请求确认，**绝不静默截断**。
- **跨会话是硬边界，但"下次开新会话问同一接口"是真实需求——解法是沉淀而非记忆泄漏**：会话内 Topic/Task/Findings 有状态、可撤销、短期；会话之间知识库无状态、已确认、长期；一次诊断确认过的正确报文**经人工确认后提升为接口目录条目**。避开跨会话记忆必然带来的权限、多租户、冲突、过期问题。

**内核/业务分界判据**（第 7 章判据的姊妹篇）：**内核只包含"所有 agent 都需要的执行原语，且这些原语的状态不需要被跨执行查询"**。Topic/Task/Findings 的本质需求是查询/聚合/按时间检索——而内核状态载体 Slots 是"按 sessionId 存的 JSON blob 快照，整取整写、不能查询"——**塞进 slot blob 会逼内核长出一套关系模型，那它就不是执行引擎了**，与"可独立成仓"直接冲突。

**落地现状（诚实口径）**：
- ✅ Task 层 + Findings 台账 + 主张变更 + 回收（TaskReaper 三阶段）+ 取消接通 + ephemeral 修复，全部真机闭环验证（追问同 taskId、attempt 1→2、槽位完整继承；否定 `#5` → RETRACTED 1 条 + SUPERSEDED 5 条 + ACTIVE 2 条）。
- ⚠️ **Topic 层暂缓——真机数据证实它会退化成 1:1**：Task 切分只由强信号（换 traceId/接口）触发，而强信号按定义就是换实体=换 Topic ⇒ 新 Task ⟺ 不同实体 ⟺ 不同 Topic。前置是"同实体内按目标切分"；在那之前跨任务关联用 P1.5 最小形态（related_findings：同会话其他任务 ACTIVE 的 ROOT_CAUSE/FIX，≤2 任务×≤2 条，**不带 [#n] 编号**防否定标错对象 + 降权标注防锚定）。
- 配套：Task QA（CONCLUDED 任务上的结论提问走轻路径直答不重跑 SOP，三态 answer/retrieve/rerun 分类全交 LLM + qa_count 配额超限零 LLM 拦截）。

**本节附带的真机踩坑（每条都是设计迭代的证据）**：
1. **CONCLUDED 任务的追问曾被 RAG 线劫持**（答成"现有资料中没有这条结论"）——findResumable 只认 SUSPENDED，与"CONCLUDED=等用户反应"设计相悖。教训：**单测能验证"组件做对了被要求的事"，验证不了"组装起来是不是对的事"——路由层尤其如此**。
2. **markSuspended 不得写 attempt_count**——调用方传的 state 是执行前旧值，照抄会冲掉 beginAttempt 的递增，表现为"追问永远开不出第 2 个 attempt"（已写进 javadoc）。
3. **ephemeral 从未真正生效**：检查在 ContextManager.persist，而运行期每节点 checkpoint 直接写 store 绕过检查——单测发现不了（逐行看都对），只有"发真实请求 + 数库里的行"才暴露。守卫带反向断言（普通会话必须照常落库）。
4. **存储大户实测反转**：原打算给 sa_message 做冷热分层，量完发现前提不成立（813 条/1.1MB ≈ 8MB/年）；真正的大户是 ops_engine_slots **76MB**——ephemeral bug 攒下的 4141 个随机 UUID 孤儿会话（不挂任何 Task，按 Task 反推的回收永远找不到）→ TaskReaper 第三阶段 NOT EXISTS 孤儿回收。
5. **换目标后槽位不更新**：抽槽"已确认值优先"，OpsRunner 又无条件灌 task.slots()，旧 interface 永远占位（模型同时收到矛盾信息）→ STALE_ON_TARGET_SHIFT 五槽清空（interface/trace_id/error/payload/response 清，environment/time/symptoms 留）+ ResumeCoordinator 委托同一判据（免两处漂移）。
6. **TTL 也作用于 CONCLUDED**（出结论 60 分钟后任务过期，隔天"结论不对"会开新任务断上下文）——已知张力，留真实使用数据决策；回收保留期从"任务终止"起算，有效保留 = sessionTtl + retentionHours 两参数要一起算。

### 4.9 Findings：可被单独否定的诊断主张（上下文架构的证据层，见 4.8）

- `AgentFinding(findingId, taskId, conversationId, kind, claim, evidence[], status, attemptNo)`：
  - Kind = ROOT_CAUSE / CONSTRAINT / FIX / RISK / ANSWER（对应四段式交付段落）；
  - Status = **ACTIVE / SUPERSEDED（被新结论取代，正常演进）/ RETRACTED（被判定为错，用户否定）**——只追加不物理删除，两种失效审计含义不同；
  - `Evidence(label, value, source)` 刻意是"指针+摘录"不是全文——**防上下文线性膨胀**。
- **抽取零模型调用**：`FindingExtractor.extract` 纯解析四段式结论文本（格式进 prompt 的价值）。
- **主张变更顺序不可颠倒**：出结论 → **先 applyDenials**（用户点名 `[#n]` 的旧主张标 RETRACTED——先于整体替换，否则会被 SUPERSEDED 吞掉否认语义）→ 再 replaceActiveWith（旧 ACTIVE 全部 SUPERSEDED + 插入新主张）。
- **注入下轮**：`renderFindings` 渲染 `- [#n] [kind] claim`（编号=下标+1，上限 10 条、单条 400 字）写 `prior_findings` 槽；跨任务关联写 `related_findings`（同会话其他任务 ROOT_CAUSE/FIX，≤2 任务 × ≤2 条）。`FindingsText` 是编号渲染唯一真身（OpsRunner 与 TaskFollowUpQa 共用防格式漂移）。
- 面试话术：**对话历史是"发生了什么"，Findings 是"我们主张过什么、哪些还站得住"**——前者随窗口滚动，后者有状态机。

### 4.10 挂起-恢复与 HITL 四形态

**内核协议件**（definition.workflow）：
- `HumanRequest(kind, prompt, slots[], context, options[], allowFreeText)`，kind = **CLARIFY（问信息）/ DECIDE（决策移交）/ APPROVE（计划审批）/ CONFIRM（关键确认）**；暂存槽 `pending_human_request`。
- `Hypothesis(claim, status: verified/disproved/unverified, evidence, nextAction)` 且 **actionable() 要求"说不出判别动作的假设不许进决策卡"**——防决策卡变成猜测清单。
- `HumanResponse(slotFills, overrides, directive, decision, autonomyHint)` + 确定性解析 `parse(text)` 识别协议前缀：
  - `#decision:<value>`（点选回传，零模型）
  - `#override:<slot>=<value>`（角标纠正，**空值=清槽**）
  - `#fill:{json}`（卡片多项打包，手写解析器无 Jackson 依赖）
  - 无前缀全文进 directive。

**挂起的引擎语义**：执行器返回 `NodeResult.suspended(nodeId, prompt)` → 保存检查点 → `RunOutcome(SUSPENDED)`；业务侧 `RunOutcomeMapper` 映射为 CLARIFY → `toClarifyRequest` 投影为交付契约 → SSE clarify 卡 + sa_message.clarify 落库。恢复重入同一节点消费 pending。

**业务侧 LLM 解释器**（HumanResponseInterpreter，prompt 资产 human-response）三条防线：
1. 点选回传（isProtocolText）→ 直接 parse，**零模型调用**；
2. 模型不可用/解析失败/回复太短 → 退化 parse（整句进 directive，最坏 = 纯协议时代）；
3. **槽名白名单**（目录 ∪ 本次问到的动态槽，编造名丢弃）+ **按当前值调和**分桶误差（当前值为空的归 fills、非空的归 overrides）+ decision 只认 pending 声明过的选项（防越权路由）+ 槽值 2000 字符截断 + 目录归一。

**自主档位**（AutonomyLevel × AutonomyPolicy）：
- L1（多问我：不代填缺省/不推断/不反查）/ L2（默认：目录缺省 + 推断 ≥0.7 + 反查 ≤2 次）/ L3（少问我：推断 ≥0.5 + 反查 3 次）。
- 优先级链：请求参数 `?autonomy=` > 回复里的 autonomy_hint > sa_agent_task.autonomy_level > L2；解析不识别回 L2——**不认识的值绝不放大自主性**。
- `AutonomyPolicy` 纯函数：**目录声明 ∩ 会话档位，只能收紧不能突破**（目录没声明 inferable 的槽，L3 也不推断）。
- 解释器抽到 autonomy_hint 顺带调档，即刻写槽并随会话持久化。

**D 系列（可讲的设计取舍）**：D7 adjust 按阶段计数；D8 只带本轮产出防 prompt 膨胀；D12 模型要 escalate 但前序已有工具证据（scratchpad 含 Observation）时降级为带补充说明的直答——"升级是转人工，不是放弃已得结论"。

### 4.11 Outcome 分派（引擎三态 → 业务五分法）

- 引擎底层：`RunOutcome/SessionState` = COMPLETED / SUSPENDED / FAILED / CANCELLED。
- 业务 `OutcomeKind`（值名与 SSE 事件协议/DiagnoseResponse.kind/eval 断言锚定，禁改名）：WITH_CONTEXT（knowledge 两段式）/ **DIRECT**（直答）/ **CLARIFY**（追问）/ **ESCALATE**（升级人工）/ **CANCELLED**（**中止没有结论，单独成值防被当正常收尾落库**）。
- `RunOutcomeMapper.kindOf` 纯函数：suspended→CLARIFY；升级判定**按可靠性排序**（轨迹终态声明 TerminalKind.ESCALATE → 到访 escalate_node → 槽含 escalate_reason）或 **FAILED 保守按 ESCALATE（不允许静默截断）**；其余完成态→DIRECT。

---

## 5. 复合范式：三 Agent 与业务图落地

### 5.1 AgentCatalog：静态目录（替代旧运行期 AgentRegistry）

> 口径校准：`RagParadigm` 枚举 / `AgentRegistry` 运行期注册表 / `WorkflowAgent` 接口均已删除；
> 现为 `business.engine.AgentCatalog` 静态目录 + 其内私有 ALIASES。

| id | label | workflowId | 意图域 | 能力位 | prompt 分层 |
|---|---|---|---|---|---|
| `ops_diagnose` | 运维诊断 | ops_diagnose_v2 | ops_diagnose | （直答无流式/引用位） | 人格层 investigate/resolve/verify；任务层 slot-extract/replan/auto-resolve/human-response |
| `knowledge` | 知识问答（eval 基线） | knowledge_qa | knowledge | STREAMING/CITATIONS/ANSWER_CACHE/SEMANTIC_CACHE/RETRIEVAL_METRICS | 任务层 rag/spell-fix；生成段 rag-answer-kb |
| `react_loop` | 工具循环检索（对照轴） | knowledge_qa_react | react_loop | 同 knowledge | 人格层 agent/react-loop；生成段共用 rag-answer-kb |

- **旧别名兼容**：`ALIASES = {naive→knowledge, react→react_loop}`，在 KnowledgeRunner 入口解析——不解析会**静默降级成 knowledge**（传 react 却跑了知识检索，结果里还记着 paradigm=react）。
- **单引擎三 Agent**（关键决策）：一个 Engine bean 注册 3 个 WorkflowDefinition + 3 个 AgentDefinition——**会话与恢复是引擎级的，双引擎断追问链**。
- `/agent/registry` 端点投影：type/label/stages/tools + workflowId/slots/stageDetails/policy。诚实细节：ops 的 stages/slots 从 OpsDiagnosisStages/OpsSlotCatalog 单一事实源派生；knowledge/react 的 stages 投影是 controller 内静态表（maxAdjustRetries 硬编码 1）——投影≠图派生，这是可改进点。

### 5.2 knowledge_qa 图（RAG 主线，v3.0.0）

```
kb_normalize → kb_classify → kb_route → kb_route_gate ─┬ 转出/短路域 → kb_shortcircuit [终态]
                                                        └ → kb_rewrite → kb_retrieve → kb_critique
                                                               ↑______________________|
                                                               （反思环 r_kb_reflection，不足回 rewrite）
```

- **七个执行器**（knowledge.node）：KbNormalize / KbClassify（**刻意只落标量槽** intent/intent_confidence/needs_retrieval——避免整坨 IntentResult 随 trace 进 SSE）/ KbRoute（第二次规则求值）/ KbShortCircuit / KbRewrite / KbCritique / KbFinish。
- **kb_retrieve 是框架 TOOL 节点**：`ToolNodeDefinition.of(RETRIEVE_NODE, retrieve_knowledge, CHUNKS_SLOT).withArgument("query", "${slots.rewritten_query}")`——**检索作为 extension tool 嵌入图**，改写真正作用于检索；工具从 ToolContext.slots 取 search_context 保持集合/预算一致。
- **反思环**：Region r_kb_reflection（Paradigm.REFLECTION）+ RegionLoop(maxRewriteRounds) + 收敛观测 sufficiency（delta<0.05 提前收敛）+ 计数槽 rewrite_round。critique 判据：`命中数 ≥ requiredHits(1) && topScore ≥ minTopScore(0.3)`（LiveRAG 895 题实测依据）。
- **跨轮累积槽 kb_chunks_all**（事故驱动设计）：critique 每轮按 ChunkIdentity 去重并入、按分降序——旧版只读单轮派生槽 kb_chunks_data（TOOL 节点每轮覆盖写），**反思轮空手会抹掉首轮命中**。

### 5.3 knowledge_qa_react 图（对照轴）

- 查询理解链抽成 `withQueryUnderstanding(builder, onKnowledgeNode, domains)` 静态构件，**两图逐字一致**——"同一句『你好』一条轴走闲聊、另一条掉进工具循环"是对照实验不可接受的。
- `react_think(LLM) → react_act(ActExecutor, 白名单仅 retrieve_knowledge) → react_decide`，react_has_calls>0 回 think（Region r_react，maxSteps=6）；每轮命中 append 进 `react_tool_chunks`（ARRAY）。
- 复用 ops 的 escalate_node（预算触顶不静默截断）。
- 与 knowledge 差异**只在节点形态**（工具循环 vs 单次 TOOL 节点），出口语义一致——这是"范式可插拔对照"的基础。

### 5.4 ops_diagnose_v2 图（诊断主线：单图五模块）

> 口径校准：诊断线是**单图五阶段模块**（intake/investigate/resolve/verify/terminal）；
> "双图"指 knowledge 线的 knowledge_qa + knowledge_qa_react 两轴。

```
extract_slots → auto_resolve(规则→日志反查→LLM推断) → auto_gate
  ├ intake_confirmed==1 && missing==0 → inv_think（重跑直通）
  ├ missing>0 → collect_slots ⇄ slots_regate（问齐环 ≤3 轮）
  └ otherwise → confirm_slots（确认门）→[动态边]→ inv_think

INVESTIGATE(inv, ≤4步)：inv_think → inv_act(get_time/query_logs/retrieve_knowledge) → inv_decide
  └ has_calls>0 回 inv_think；否则 → replan_1 ─ continue→res / adjust(额度1)→inv / escalate
RESOLVE(res, ≤5步)：res_think → res_act(retrieve_knowledge/validate_request) → res_decide → replan_2
VERIFY(ver, ≤3步, streamThink)：ver_think → ver_act → ver_decide → conclude [FINISH]
（每阶段 act 均有动态边 → escalate_node [ESCALATE 终态]）
```

**单一事实源 `OpsDiagnosisStages`**：`Stage(prefix, name, title, regionId, tools, maxSteps, replanNode, outputGate, streamThink)` 派生一切命名——节点 `{p}_think/act/decide`、执行器 `ops-act-{p}`、守卫 `ops-max-{p}`、prompt 资产 `workflow/ops_diagnose_v2/{name}`、计数槽 `{p}_round`。**改阶段只改一处**。

**stages 模块化 + provides 对账（"图是唯一事实源"）**：
- `StageModule` 接口：`key()/entryNode()/provides()/declareGraph(纯数据，静态可测)/wireRuntime(装配期)`；
- `OpsDiagnoseWorkflowFactory.reconcile`：从 start 节点 BFS 收集图可达节点引用的执行器集，与各模块 provides() 联集对账——**missing（图引用无模块提供）或 orphan（模块提供图未引用）任一非空即启动失败**。图与装配永不漂移。

**关键节点执行器**：
- `AutoResolveExecutor`（问用户之前的自救层，三层递进）：① 规则（RelativeTimeParser 相对时间换算"最近10分钟"→ISO 窗口 / 接口路径正则 / InterfaceCatalog 唯一匹配）→ ② **LogBackfill 日志反查**（一手信息源，放 LLM 之前——关键字(业务键>接口名>错误摘要)>时间窗 → query_logs 取出现最多 traceId → traceId 精查 → 从日志行提取接口/错误/报文；空结果**如实交代**"已在窗口内按关键字检索：未命中"）→ ③ LLM 推断 ≤1 次（置信度过线才生效）。全部落 inferred_slots 台账。
- `AskMissingExecutor`：**一次问齐**（缺 3 项只问一次，不挤牙膏）；重入先解释器（抽到槽就不再重复抽槽）→ 兜底抽槽 → LogBackfill.rerun（只有 intake 挂起恢复才重跑反查）；反查未命中时**卡片摊开 trace_id/time 关键槽核对入口**（领读句）。
- `ConfirmExecutor`：齐备后出 CONFIRM 确认单（全部槽+来源+依据）；`#decision:confirm` 或保守确认语放行；用户改写机器值 → provenance 转 user_override。
- `ReplanExecutor`：四态 continue / adjust（每阶段额度 1）/ ask_human / escalate；ask_human、escalate、adjust 耗尽统一收敛为 **DECIDE 挂起**（escalate 从死亡终点改为决策移交——P1 的核心改动）；解析失败/模型不可用按 continue。
- `ConcludeExecutor`：取**最后一个非空阶段产出**（ver→res→inv 优先级）+ 剥 JSON 外壳 + 附 auto_resolve_note（自主性透明化：机器补了什么一目了然）。

**ActExecutor（两域共用，workflow/common）——阶段内工具循环**：
- **槽位目录参数化**：构造参数 `askableSlotNames`（ops 传 OpsSlotCatalog.askableNames()，react 不传）——**共用层不感知任何域的槽位目录**。
- 执行流：① 幂等重入（pending_ask 非空→消费 user_clarify 答复回环）→ ② 预算闸门（llm_calls ≥ maxLlmCalls(24) → 动态边 escalate_node，**绝不静默截断**）→ ③ 解析 think 单行 JSON 意图（详见第 6 章）。
- 出口护栏 `gateOutput`：产出 JSON 块过 JsonSchemaValidator，不过**回环修正**（不是丢弃）。

### 5.5 SubWorkflow / Parallel（诚实口径：内核有、业务未用）

- `SubWorkflowNodeDefinition(id, workflowId, inputMapping 子槽→父表达式, outputSlot)`：`SubWorkflowNodeExecutor` 经 **BranchInvoker**（与主循环同一份执行实现——"并行/子工作流不绕过守卫、过滤器、拦截器"）逐节点执行；**独立槽位容器**（父子状态互不污染）；上限 = config.maxSteps（默认 100 步/次）；**不支持挂起**（子节点 suspended 直接 failed）。
- `ParallelNodeExecutor`：branches + maxConcurrency + 虚拟线程 + JoinStrategy.ALL（否则首个成功分支）；全败才 failed。
- **与旧内核的差距（面试诚实加分项）**：新内核**没有**递归深度计数、跨工作流环检测（自引用 a→a 会逐层拿 100 步预算直到栈溢出）、预算总账（子工作流/并行分支的 LLM/token 消耗不进父会话 QuotaEnforcer 账本）——已知债务，当前多 agent 协作形态是"编排层路由 + 单引擎三 Agent 平行图"，不靠图内嵌套。

### 5.6 扩展故事的真实形态（旧口径"三步零改"已部分不成立）

新增一个 workflow agent 的当前步骤：① AgentCatalog 加 Entry；② 图工厂 + 执行器在 AgentEngineConfiguration 装配（ops 型还需 StageModule 参与 reconcile）；③ 新意图域加 RouteRule @Component；④ 前端基本零改（registry 自动刷新）。
不再"零改"的三处（诚实讲）：ChatOrchestrator 显式选择分支硬编码 ops 比较；AgentBranchDispatcher 目前只绑 OpsRunner；KnowledgeRunner 出口槽位按 agentId 硬编码两轴。"引擎零改"仍成立（内核对业务图无感知）。

---

## 6. Prompt 资产化与工具调用

### 6.1 Prompt 资产五表（platform-prompt）

```
sa_prompt(key 唯一 + current_version_id 指针)      ← key 与版本分离
sa_prompt_version(prompt_id, version_no, content, change_note)  ← 不可变只增不改
sa_prompt_bundle(name, agent_type 可空=通用包)      ← 能力包
sa_prompt_bundle_release(bundle_id, release_no, items JSONB)    ← {key: versionId} 全量快照，不可变
sa_prompt_binding(agent_type UNIQUE, base_bundle_id, overlay_bundle_id)  ← 活包名引用
```

- **classpath 首次种子 + DB 运行态真相**：`importBaseline()` @PostConstruct，缺 key 建档发 v1；已有 key 且内容漂移**只记差异不自动建版本**（防开发改码被静默压制），显式 `/prompt/{key}/sync-from-code` 一键收编。
- **版本管理**：时间线（新→旧）、逐行 LCS diff（前端算，数据已在手不发请求）、发版（appendVersion 只增新行+指针前移）、回滚 = **以旧版本内容发新版本**（历史只增不改语义不变）。
- **能力包三层**：基座包（公共区）+ 特化包（骨架特有 + 对基座覆盖）；**release 存 versionId 而非 versionNo**——发布后 key 再发新版不影响已发布 release（不可变快照）；**绑定是活引用**——每次装配解析各包"当前 release"合并，基座发版全骨架自动生效。
- **切换前即时校验**：probe 合并后 requiredKeys ⊆ contents，缺 key 抛异常列出明细——"绑定是活引用，删包/缺 key 会让骨架装配静默回退，属于配置悄悄失效，绝不允许"。
- **会话粘性**：resolveByReleases 按 `bundleId:releaseNo|...` 精确重建当时快照（release 不可变故可复现）；spec 失效 warn 回退当前绑定不阻断。
- 删除有绑定的包：**拒绝并列出骨架名**（连带删 release，prompt 资产不动）。

### 6.2 三层增强 + 指纹（Prompt 与缓存的连接点）

- 三层：**链路级**（rag/pipeline/rag-answer-system，跨域输出契约）+ **Agent 人格层**（agentPromptKeys）+ **Workflow 任务层**（workflowPromptKeys）+ 生成段 answerPromptKey（允许共享）。
- **跨层/跨 Agent 同 key 装配期报错**（putStrict：人格/任务层 key 须各归各的命名空间，拼错即启动失败）。
- **AgentFingerprint 三元组** `(agentId, workflowId, promptHash)`：TreeMap 按 key 排序 → `key + SOH + content + STX`（不可见分隔符防拼接歧义）→ SHA-256 → **前 12 字节 24 hex**。解析失败回退常量。
- **指纹进缓存 key**（F6 的落地）：`CacheKeys.answerKey(question, paradigm, docver, promptHash)` + 语义缓存 WHERE prompt_hash=?——**改任一层 prompt，精确与语义缓存自动失效，无需任何清缓存操作**。指纹同时贯穿 sa_agent_trace、SSE trace 事件、eval。

### 6.3 PromptStorePromptProvider 三级解析（运行时读哪个版本）

```
① 装配期动态模板（runtimeTemplates：OpsPrompts.compose 拼的"正文+工具协议块"）
② 绑定包覆盖（基座当前 release + 特化当前 release merge，同 key 特化胜出）
③ classpath 基线兜底
```

- **为什么组合模板最高优先**：协议块是代码拼装的（工具 schema 渲染），必须始终最新；资产正文（DB 可改）只占模板的一部分。
- **工具协议行从 schema 渲染不手写**：`describeTool(schema)` 产出 `id(参数?,参数?)：说明`——历史手写 topK/topN 漂移白烧一整轮工具循环的教训。
- 无绑定时 overridesFor 返回空 map = 只读 classpath 基线，未绑定 Agent 行为零变化（灰度安全）。

### 6.4 工具调用：JSON 意图协议（口径校准）

> 旧口径 `internalToolExecutionEnabled=false` 手动驱动原生 function calling、`tool-call-mode: json` 降级开关——**已随旧内核删除**。当前：**工具循环固定 JSON 文本协议**（prompt 自带协议块），SpringAiModelProvider 只搬运消息文本不投影工具契约。

**三代演进（面试讲"你们用过原生 function calling 吗"——用过，最终显式取舍为 JSON）**：

| 阶段 | 工具调用方式 |
|---|---|
| 第一代（旧业务层内核，~2026-09） | 原生 function calling 手动驱动（internalToolExecutionEnabled=false + ToolLoopEngine 自解析 toolCalls）；tool-call-mode: json 是降级逃生门 |
| 第二代（agent-framework 重建，09-12） | 双协议 ModelPort：NativeToolModelPort / JsonProtocolModelPort 可切换 |
| 第三代（agent-core 图范式内核，09-18 起，现役） | **固定 JSON 意图协议**；tool-call-mode 配置键删除，yaml 注释明示"已随旧内核移除" |

**内核保留了原生通路（"不是不能，是不选"）**：ModelGateway 具备 `ModelRequest.withTools(List<ToolSchema>)`（携带工具契约）+ `ModelResponse.toolCalls()`（返回 ToolCall）+ `LlmNodeExecutor` 写 `{outputSlot}_tool_calls` 派生槽——换一个投影工具契约的 ModelProvider 实现即可启用；架构可切换，业务上做了固定 JSON 的显式取舍。

**协议格式**（单行 JSON，禁止 markdown 围栏，四类意图）：

```json
{"tool":"query_logs","args":{"trace_id":"3b5f..."}}
{"ask_user":"请提供接口路径","slots":["interface"]}
{"escalate":"缺少生产环境权限"}
{"answer":"结论文本..."}
```

- **解析容错**（ActExecutor.parseLLMThink）：剥围栏 → 取首 `{` 到末 `}` Jackson 解析 → 优先级 tool→ask_user→escalate→answer → **解析失败把原文当 ANSWER 终稿**（对齐参考语义：模型已经给了内容，不因格式丢弃）。
- **为什么放弃原生 function calling**（面试必被问）：① DeepSeek 等模型 JSON 遵循率经联调验证可靠；② 意图协议把 ask_user/escalate/answer 变成与 tool 平级的一等意图——原生 function calling 只能表达"调工具"，控制流意图还得另造机制；③ 协议块随 prompt 走三层资产化与指纹（改协议自动失效缓存）。代价：不享受供应商侧工具调度优化——在自建引擎里这层本来就要自己管。
- **观测回喂**：Thought/Action/Observation 追加进 Region 局部槽 scratchpad；**Observation 截断 4000 字符且显式标注**"共 N 字符只保留前 4000，如需完整内容请缩小查询范围后重查"——防静默截断诱导残缺结论。工具错误统一成 `[工具错误] ...` 回喂不中断循环。

### 6.5 工具子系统全貌（内核契约 → 业务装配）

- **两条调用路径（先分清，面试易混）**：
  - **路径 A：图内 TOOL 节点静态直调**（knowledge 线 kb_retrieve）：`ToolNodeDefinition.of(RETRIEVE_NODE, "retrieve_knowledge", CHUNKS_SLOT).withArgument("query", "${slots.rewritten_query}")`——调用是**图声明的**，参数由槽位模板注入（改写真正作用于检索）、产物写指定槽；零模型决策，确定性执行。
  - **路径 B：模型驱动的意图协议**（ops / react 线）：think 输出 `{"tool":...,"args":{...}}` → ActExecutor 解析 → 阶段白名单校验 → 执行管道。工具、问用户、升级、直答是**四个平级意图**，不是"工具调用 + 特殊分支"。
  - 理念：**检索就是一个 extension tool**（retrieve_knowledge 与 query_logs 同权）——这是"单引擎三 Agent 统一编排"的基础。

- **契约**：`Tool` 接口 = `id() + schema(): ToolSchema + invoke(ToolInput, ToolContext): ToolResult`——实现只管"怎么做"，权限/缓存/超时/重试归执行器与中间件。
- **自描述**：`ToolSchema(name, description, List<ToolParameter>, returns)`——**类型化参数清单而非 JSON Schema 标准**（自有简化契约）；自带 validate（必填缺失+未声明参数）与 applyDefaults。
- **注册**：`DefaultToolRegistry`（id 键双 map：tools + definitions）；业务装配 `AgentEngineConfiguration`：单 sharedRegistry 注册 RetrievalTool（retrieve_knowledge）/ QueryLogsTool / ValidateRequestTool / CurrentTimeTool + **MCP 工具同权注册**。
- **执行管道** `DefaultToolExecutor.execute` 固定链路：schema 校验 → ToolPolicy 守卫（Deny/AskApproval 转 failed）→ BEFORE_TOOL 守卫 + BEFORE_TOOL_CALL 过滤 → AROUND_TOOL 拦截链（缓存/超时/重试/熔断/追踪，策略来自 ToolDefinition）→ invoke → AFTER_TOOL_RESULT 过滤 + AFTER_TOOL 守卫 → 指标/事件。**失败以 ToolResult 返回不抛异常**——单个工具不拖垮工作流。

- **一次工具调用的完整生命周期**（路径 B 端到端，背这条线）：

```
① think 节点：LLM 按 prompt 协议块（从 schema 渲染，含阶段白名单）输出单行 JSON 意图
② ActExecutor.parseLLMThink：剥围栏 → Jackson 解析 → 意图优先级 tool > ask_user > escalate > answer
   （解析失败原文当 ANSWER 终稿——模型给了内容不因格式丢弃）
③ 预算闸门：llm_calls ≥ maxLlmCalls(24) → 动态边 escalate_node（绝不静默截断）
④ 阶段白名单：allowedTools.contains 否决（prompt 里也没渲染该工具——模型根本不知道它存在）
⑤ DefaultToolExecutor 管道：校验/守卫/过滤/拦截/配额 → tool.invoke → ToolResult
⑥ 回喂：Action+Observation 追加 Region 局部槽 scratchpad（Observation 截 4000 字符显式标注；
   错误统一 [工具错误] 文本回喂，不中断循环）；命中 chunks 跨轮累积 tool_chunks
⑦ 循环：decide 节点按 slots.has_calls > 0 条件边回 think（Region maxSteps 守卫）
⑧ 出环：模型输出 {"answer":...} → 写 stage_output、has_calls=0 → 出口护栏 gateOutput
   （JSON 过 schema 校验，不过回环修正）→ 走出 Region 进 replan/下一阶段
```
- **白名单三层**：阶段级（OpsDiagnosisStages.tools + prompt 协议块同步渲染"白名单之外一律不可用"）/ Agent 级（ToolPolicy.only，AgentDefinition 声明：OPS 四工具、KNOWLEDGE/REACT 仅 retrieve_knowledge）/ Region 级（loopGuard maxSteps）。
- **MCP 桥**：`McpExtensionToolSource`（开关 rag.chat.agent.mcp.enabled 默认 false）→ `McpExtensionTool`：id = **mcp_ 前缀防重名**（注册表重名即冲突）；inputSchema JSON Schema 解析投影为 ToolParameter 清单（解析失败按无参工具，参数透传 MCP 端校验）；异常 → ToolResult.failed 错误回喂。
- **QueryLogsTool 实战细节**：traceId 精查（缓存 24h，历史不可变）/ 时间窗模糊查（60s 分钟桶）；进程内 TTL 缓存（记录在案的有意偏离 Redis——日志反查要保实时）；**service_name 排除自我循环**——平台把 prompt 打进日志，prompt 模板自带"请求报文/业务响应"字样会被反查准入标记命中，把意图分类器 JSON 当业务报文抓回来自我维持；修法 = 两条查询路径都拼 `service_name != 'a' AND ...` 排除平台自身。

---

## 7. 编排层：ChatOrchestrator 决策链

### 7.1 决策骨架 + 8 协作类

`ChatOrchestrator<S>` 泛型 S 是传输载体（对编排层完全不透明，只透传给 DeliveryPortFactory）。8 个协作类各自带决策链的一步：

| 协作类 | 步骤 | 职责 |
|---|---|---|
| ResumeCoordinator | 0 | 查活动任务，判定本轮消息是否归入既有诊断任务 |
| RouteEvaluator | 1 | TraceIdExtractor 正则提取 + 规则表第一次求值（intent=null，正则短路类可命中） |
| DocReferenceClarifier | 2 | "这篇文档"指代悬空检测（纯正则 + 两次轻查询） |
| AgentBranchDispatcher | 分支 | workflow 型 agent 统一入口：合并槽位→DegradeGate→OpsRunner→按 OutcomeKind 分派交付 |
| AnswerCacheCoordinator | 6.5 | 两级答案缓存：exact→语义、频率准入、热度 TTL、语义命中回写 exact、收尾写回 |
| RagAnswerStreamer | 10 | RagContext 组装、citations 先行、流式生成、完成回调落库+缓存写回、停止时部分回答落库 |
| ChitchatResponder | 8.3 | 非检索意图直答（**会话范式锁定**：历史非空且检索有货时 chitchat 判定不生效） |
| DegradeGate | 包装 | tryAcquire 失败即替调用方 emitDegraded（ops/RAG/闲聊三处共用） |

### 7.2 意图路由优先级（六层，必考）

```
① 会话恢复（最高）：SUSPENDED 恒归任务；CONCLUDED 默认归，例外 targetShifted
   （新 traceId/接口路径 ≠ 已确认值，确定性判据零模型）→ chainTraceId 续链（OTel 非记录 span 强制挂链）
② traceId 短路：TraceIdShortCircuitRule(priority 10) 正则命中直进 ops + 预填 trace_id 槽，省 2 次 LLM
③ agentChoice 显式选择：?agentChoice=ops_diagnose 跳过意图分类直进 ops
   （防"帮我看看"被误判闲聊覆盖用户显式选择）
④ ?agent= 兼容语义：仅 RAG 链内编排（paradigm 轴 knowledge/react_loop）；
   旧客户端默认携带 agent=knowledge 不得当显式选择，否则意图路由被无声旁路
⑤ 意图识别：图内 kb_classify → kb_route 第二次规则求值；OpsIntentRouteRule(priority 50)：
   domain==ops_diagnose 且 agentChoice≠knowledge（压制劫持——库里可能有报错说明文档）
   且 confidence ≥ diagnoseMinConfidence（代码缺省 0.6，生产实配 0.8——实测"疑似"级 0.6
   把"Refresh-Token"追问劫持进诊断答非所问后收紧）才劫持
⑥ 默认知识检索
```

- 设计理念：**"用户想问什么"与"由谁来答"解耦**；每步设短路出口；显式选择 > 意图识别 > 默认。
- 双参数语义：前端显式选择时 agent+agentChoice **双发同值**；Auto 档两个都不发（纯前端概念）。

### 7.3 全局异常与 SSE 错误（异常处理体系一）

- **全局异常**（platform-console GlobalExceptionHandler）：NoResourceFound→404 / MaxUploadSize→413（带人类可读上限）/ IllegalArgument+ClientException→400 / ServiceException→500 带栈 / 兜底 500。统一 `{code, message}`。
- **SSE 生命周期**：订阅 error → partial 落库（空则占位"[stream error]"）→ completeWithError；取消（dispose）→ **部分回答落库**（AtomicBoolean 防双写）；所有 send 吞 IOException（断连/序列化失败不阻断主链路）；增强类事件失败只记 debug。
- **取消双保险**：`POST /chat/cancel` = ActiveStreamRegistry 停 SSE 流 **+ OpsRunner.cancelDiagnosis**（引擎会话置 CANCELLED——交付层 dispose 引擎不知情，靠引擎在**节点边界**检查状态收尾：正在执行的节点打断不了但不再开下一个；任务 release 放回可恢复态，**绝不把中止记成 CONCLUDED**）。
- **礼貌拒绝**：降级期 emitDegraded"系统当前繁忙（缓存服务降级中）"；升级 emitEscalate"排查在这里卡住了…请补充信息"。

### 7.4 引擎失败处置（异常处理体系二，口径校准）

> 旧文档"阶段级策略 AS_IS/RETRY/SKIP/ESCALATE/FAIL"在当前代码中**不存在**。真实形态：

- **节点失败 = fail-fast**：执行器抛异常 → NodeResult.failed(类名:消息) → 整 run FAILED；重试语义由上层承担（replan adjust 环 / 工具级 RetryPolicy 定义存在但业务装配未启用中间件）。
- **预算三闸**（全部"显式升级不静默截断"）：① ActExecutor 预算闸门 llm_calls≥24 → 动态边 escalate_node；② 全 run maxSteps=100 → FAILED；③ QuotaPolicy（token 200 万 / 工具 60 次 / 墙钟 300s）→ QuotaExceededException → FAILED → **RunOutcomeMapper 保守按 ESCALATE 交付**。
- **FAILED ≠ 无输出**：EscalateExecutor 输出必带"此前各阶段产出 + 为什么需要人工"——升级时已查到的东西不丢。
- **eval 失败隔离**：单条失败记 error 指标（score=-1）不中断 run；全部零召回才 run FAILED；进程被杀遗留 RUNNING 由启动恢复统一标 FAILED；retryRun 只重跑原 run 实际评测过的条目。
- **入库失败**：节点 fail-fast 断链 + sa_document FAILED + errorMsg；FAILED 也 bump docver（保守失效）；MQ 消费失败回写 FAILED 不抛异常（不重投）；FAILED 任务可被重投重跑（CAS 允许 FAILED→PROCESSING）。

---

## 8. 缓存与稳定性：五层缓存 + 频率准入 + 熔断降级

### 8.1 五层 Redis 缓存 + 语义缓存

| 层 | key 前缀 | TTL | 准入阈值 |
|---|---|---|---|
| 意图 | rag:cache:intent: | 30m | 只缓存 confidence≥0.7；**历史非空旁路不读不写**（裸关键词意图由历史决定，缓存会串味） |
| 检索结果 | rag:cache:retr: | 10m | 2（窗口内出现 ≥2 次才写） |
| 日志工具 | rag:cache:logs: | 60s（trace 精查 24h） | — |
| 精确答案 | rag:cache:ans: | 6h | 2 |
| 查询 embedding | rag:cache:emb: | 7d | — |
| 语义答案 | sa_cache_answer（pgvector 1024 维 HNSW） | 7d / 1000 行 | 写入**刻意不做频率准入**（字面不同的重复问题正是语义层靶子） |

- **key 设计**：SHA-256 截断 24 hex；段间 `(char)1` 不可见分隔符（防拼接歧义）；检索 key **硬约束含 SearchContext 全参数 + docver**（eval 参数扫描正确性）；答案 key = 问题+范式+docver+promptHash。
- **失效三板斧**（无 keys 扫删）：docver 版本化（旧 key 等 TTL 漂走）、prompt 指纹进 key（改 prompt 自动失效）、TTL 只是内存旋钮非正确性旋钮。
- **语义缓存命中条件**：cosine ≥0.95 **且** paradigm、docver、prompt_hash 三元组精确匹配（收很紧防"相似≠同义"："怎么冲红"vs"冲错了怎么撤销"距离近但答案不同）；命中回写 exact（下问免 embed 直达）。
- **带历史答案缓存整条旁路**（关键正确性决策）：缓存 key 无会话维度，带指代的追问句（"它的配置呢"）不同会话字面相同，写进缓存会被下个会话重放错答案。
- 注意：生产当前 `rag.cache.enabled: false`（留档可一键恢复）——面试如实讲，关闭是某阶段排查的保守决定。

### 8.2 频率准入：进程内 LeapArray（Sentinel 朴素版）

- **结构**：每 key 16 个环形时间桶（15m 窗口，每桶约 56s），每槽一个 AtomicLong 打包 **`桶纪元<<32 | 计数`**——槽纪元过期自然归零重写，**无需清理线程**；写 O(1) CAS，窗口求和 O(16) 只累加纪元在窗内的槽。
- **为什么进程内化**（2026-09-11 决策）：原 Redis INCR+EXPIRE 每请求固定多 4 次 RTT，缓存全命中时统计开销**反超缓存收益**；且 Redis 故障连带频率策略失效。进程内后热路径零 Redis 调用、降级期准入照常。取舍：多实例计数偏低 / 重启清零（单实例可接受）。
- **决策纯函数** CacheFrequencyPolicy：`admits(windowCount≥threshold)`（EVAL 旁路：metadata.intent==EVAL，参数扫描每组合都是新 key，不旁路 eval 提速归零）；`scaledTtl`：窗口 ≥5 → TTL×4、≥20 → ×10（**只延长不缩短**——key 含 docver 所以 TTL 只是内存回收旋钮，延长零正确性风险）。
- **写门槛防长尾污染**：retrieval/answer 默认 2——一次性查询不进缓存。

### 8.3 熔断 + 降级 + bulkhead（稳定性铁三角）

- **RedisHealth 共享断路器**：CLOSED →（连续失败 ≥5）→ OPEN（冷却 30s，isDown=true 期间**所有调用方直接旁路零 Redis 调用**——慢死形态：连接挂起 × 每请求 7-8 次操作 × 3s 超时的无效等待归零）→ HALF_OPEN 放一次探测 → 成功 CLOSED / 失败回 OPEN。刻意半开探测而非瞬间全通。
- **RedisCacheStore 全防御**：get/put/touch 全 try-catch（含 Redisson 适配层 StackOverflowError）；get 失败 = miss——**Redis 故障缓存旁路，主链路零感知**。
- **DegradeGuard 动态限流**：`Semaphore(degrade-limit=8)`；健康期 `Lease.noop()` 零信号量开销直通；降级期拿不到许可 → rejected 计数 + tag cache.shedding → 礼貌拒绝。理念：**缓存保护消失时给下游 API 留活口，宁可礼貌拒绝也不全体排队拖垮**。
- **检索侧不另设闸**：ragContextExecutor 固定线程池（core=max=8；JDK 池"队列不满不扩 max"，core=2 时排队任务会在执行前被 orTimeout 判超时静默丢弃——踩过）+ ContextPropagatingTaskDecorator（跨线程传播 MDC+OTel）= **天然 bulkhead**。
- 连接超时收紧 1s（connect-timeout）防挂起。

---

## 9. 评测体系：黄金集 + 三件套 SPI + Langfuse 闭环

### 9.1 数据模型（五表）

```
sa_eval_dataset（数据集） → sa_eval_item（黄金条目：question + expected_doc_ids + expected_answer）
sa_eval_run（跑批：param_snapshot JSONB 快照 + aggregate_metrics） → sa_eval_metric（每条×每指标一行）
sa_eval_item_trace（2026-09-18 拆表：轨迹专表，每 run+item+attempt 一行）
```

- **EvalSample 四元组**（对齐 RAGAS/DeepEval 形状）：question / contexts（**线上同款 RagContextAssembler 组装**）/ generatedAnswer / ground_truth。
- **IRT 列**（sa_eval_item）：irt_diff（难度 b）/irt_disc（区分度 a）/acs（各系统平均正确率）/acs_std；difficulty E/M/D/HD 由全量 irt_diff 四分位定档（取全量防切点漂移）。irt_diff 与四分位平均 ACS 皮尔逊相关 **-0.970**（自证有效）。
- **LiveRagImporter 回流**：hf-mirror parquet → DuckDB 随机抽样 → 支撑文档走真实 IngestionEngineService 入库（expected_doc_ids=taskId）；**按源 urn doc_id 跨题共享入库**（实测 1032 引用→970 唯一文档，不共享会造成 62 个冗余副本 + 38 条"召回了内容却记 0 分"）；入库即归集同名 collection（检索范围隔离自动生效）。
- **线上 badcase 回流**：source=feedback 标记。

### 9.2 框架三件套（SPI 开闭模式，@Component 即注册）

```
EvalScorer（打分器）：name() + score(EvalSample) → List<EvalScore>
EvalResultSink（结果槽）：onRunStart / onItemResult / onRunFinished
EvalRunner（跑批）：异步 + 定并发（全局 Semaphore(2 runs) fail-fast + run 内 8 虚拟线程 + 全局单例 itemLimiter）
```

**指标实现（含口径细节，面试易被追问）**：
- **Recall@k** = 前 k 命中数 / |expected|（期望空=1.0）
- **Precision@k** = 前 k 命中数 / **k**（分母固定，缺位视为不相关）
- **MRR** = 首个命中 1/(i+1)，无命中=0
- **nDCG@k**：DCG=Σ rel/log2(i+2)；**IDFG 用 min(|expected|, k) 而非实际召回数**——run82 实测：用 limit 会让"期望 2 召回 1 命中"得满分，与 recall 背离
- **context_recall / context_precision**：citations∩expected / |expected| 与 命中/|contextDocIds|——与 recall@k 的差值即"contextTopK 裁剪后'检索到了但没进上下文'的损耗"；comment 带文件名对照（期望逐个 ✓/✗ + 实际清单）
- **answer judge**（LLM-as-judge）：prompt 资产 eval/answer-judge，输出 correctness/faithfulness 0-10 ÷10 归一；`firstJsonObject` 平衡花括号提取（抗围栏/尾逗号）；失败跳过不阻断
- 输入：**文档级去重保序**（retrievedDocIds）

### 9.3 评测即真实（三个"同一"）

同一 KnowledgeRunner、同一引擎同一 RetrievalTool（参数一致）、同一 RagContextAssembler（"评测即真实输入"）——评测跑的就是线上代码路径，`RunContext.forEval` 只钉死 forceRetrieval=true + rewrite 开关映射 FORCE/OFF。**此前自己算 rewrittenQuery 塞 SearchContext 是"开关接在断线上"**（教训）。

### 9.4 Langfuse dataset run 推送

- 惰性同步：条目问题文本精确匹配既有 dataset item，miss 则创建（input=问题、expectedOutput=标准答案）。
- `runName=eval-run-{runId}`；每指标 score **同时挂 traceId + datasetRunId**——UI 可跨 run 对比、可下钻到单条 trace。
- 单 item 独立 root trace（**先抢信号量再开 trace**——排队时长不该记进 span duration）。
- **推送不拖累评测**：固定 8 虚拟 worker + 有界队列 4096；PushTask 只捕获小字段（不钉住含 22KB agentTrace 的 EvalSample）；队满丢弃 + 60s 节流告警（"指标已落库，丢推送不影响结果"）；无凭据自动 no-op。
- 自建 LangfuseDatasetAdminClient 补 jar 只有 list/link/score 无 create 的缺口。

### 9.5 多范式对照（公平性三保证）

- Live 并发对照：Promise.all 各范式独立 conversationId 并发流式，按列渲染轨迹树+答案+延迟。
- Eval 指标对照：**N 个范式传同一组 itemIds**（前端抽样一次——原先各范式各自 triggerRun 后端各自 shuffle 跑不同题子集"对照失真"）。
- 检索型范式（knowledge/react_loop）同引擎同工具同指纹口径，差异只在节点形态——对照才归因得了"范式"这个变量。

---

## 10. 可观测性：从一行日志到 OO 深链

### 10.1 telemetry starter 门面（自研，已发布坐标）

- `com.github.apprentice-ol.llm-observability`：**@TelemetryStep**（注解/AOP）+ **TelemetryTemplate**（编程式：step/openTrace/Flux 装饰/tag/traceInput/traceOutput）+ **TelemetryLogger**（conversationOutput 门面）。
- 数据流：@TelemetryStep → TelemetrySpan → processor 链（摘要→截断）→ exporter 链 → OpenObserve/Langfuse + MDC（step/step_id 进日志）。
- 全仓 17 处 step 埋点：rag.chat（入口）/ rag.query.normalize（模板手动——AOP 盲区）/ rag.query.rewrite / rag.intent.classify / rag.retrieve / rag.channel.*（bean getName 自动展开）/ rag.postproc.* / rag.rerank.call / rag.answer（Flux captureOutput，span 延迟到首次订阅）/ rag.chitchat。
- **TelemetryDimensions**：启动期 `dimensionOnOutput(IntentResult.class, r -> {intent: domain})`——维度由观测层从返回值自动提取，业务管道不申报观测。
- **SpanIoLimits**：`telemetry.limits.max-span-io=8192`（单 span 8KB）——服务器实测不截断时单 trace 22~36MB、top wa 93% 打满磁盘致 OO 崩溃，且 span 批次超 OO 单请求上限整批 400 被弃、traces 断流。
- **双后端**：统一 OTLP；collector.enabled=true 时经 otel-collector 扇出（traces→OO+Langfuse、logs→OO）；=false 直连。入库链 LLM span 由 Spring AI ChatModel observation 自动产 gen_ai.*，无需手工埋点。

### 10.2 traceId 三方关联（消息 ↔ 轨迹 ↔ 链路）

```
每行日志自动带 [traceId,spanId]（OTel→MDC，不手动拼）
→ SSE meta 事件（流末尾 messageId/traceId/paradigm）→ 前端气泡绑定
→ 气泡查轨迹：本轮走内存 TraceView（SSE trace 事件，流前+收尾两版）；历史走 GET /agent/traces/by-message
→ OO 深链：trace-details?trace_id=...&from=&to=（**微秒**窗口，按消息时间 ±10min；缺 from/to 会被 OO 重定向到列表页）
```

### 10.3 三个必讲的观测工程细节

1. **SpanExportingPredicate 导出白名单**（而非 Sampler）：只导出 /chat/stream 前缀的 SERVER span，其余 HTTP（CRUD/上传/actuator）整条不发。**为什么不用 Sampler**：micrometer bridge 在 startSpan 之后才补 span 名/属性，采样时点只有 `<unspecified span name>`+空 attributes；导出时 FinishedSpan 的 url.path/http.route 已完整。
2. **LlmCallGuard + ContextSnapshot**：阻塞 LLM 调用放 llm-guard-* 虚拟线程限时等待；**切线程前 ContextSnapshotFactory.captureAll() 捕获、worker 内 setThreadLocals() 恢复**（MDC/OTel）——否则 chat/embedding observation 脱离当前 trace 成碎片根 trace。同理：内核 Timeout 拦截器丢虚拟线程 → TaskPropagation.install(ContextPropagator::wrap)。
3. **NonRecordingSpan 续链**（诊断追问不断链）：显式 chainTraceId 时 `SpanContext.create(traceId, randomSpanId, sampled, default)` + `builder.setParent(Context.root().with(Span.wrap(forced)))`——以非记录 span 强制挂到既有 trace 作根，引擎 span 落进业务链；ambient 父仅回退。顺序不能反，否则追问轮 chainTraceId 永不生效、一次诊断散成 N 段碎片（2026-09-21 真机踩坑）。

### 10.4 sa_agent_trace 与拆表

- 字段：conversation_id/message_id/trace_id/指纹三元组(paradigm/workflow_id/prompt_hash)/question/steps JSONB（TraceStepView：stepIndex/action/thought/inputSummary/outputSummary/latencyMs/detail）/llm_call_count/total_latency_ms；异步落库（chatPersistExecutor 虚拟线程，失败仅 warn）。
- **TraceView 是唯一轨迹契约**：SSE trace 事件、sa_agent_trace、eval trace JSONB 三处共用（不变量）。
- **LocalDateTimeTzSerializer** 带 JVM 时区偏移——防前端 new Date 时区错位、OO 深链窗口错位（应用连接 Asia/Shanghai、psql 会话 Etc/UTC 的时区陷阱）。
- **eval 拆表瘦身**：轨迹原先随 sa_eval_metric 每指标行复制（9 指标 × 22KB，395MB 表里 352MB/89% 冗余）→ 独立 sa_eval_item_trace 每 run+item+attempt 一行；先写指标后写轨迹且轨迹独立吞异常——"轨迹是旁证，不能拖累指标"。
- 采样：probability=1.0 全采样 + 降量三道闸（导出白名单 / 内容限额 / eval 独立 root trace）才是实际主力。

---

## 11. 设计理念总纲（跨领域原则，回答"你的设计哲学"用）

1. **配置悄悄失效是红线**：prompt 绑定切换前 probe 校验缺 key 即抛；PgEngineSessionStore 读失败抛异常不返 empty（empty=重开新诊断）；FAILED 保守按 ESCALATE；预算触顶显式升级——宁可失败得响亮，不可降级得无声。
2. **单一事实源 + 派生**：OpsDiagnosisStages 派生一切命名；图与模块 provides 对账（图是唯一事实源）；FindingsText 唯一渲染真身；引擎 Session/Cursor/Slots 与业务 task 分层不混表。
3. **正确性进 key，TTL 只是内存旋钮**：docver、promptHash、SearchContext 全参数进缓存 key——失效是"结构必然"不是"定时行为"。
4. **机制与策略分离**：内核只做机制（挂起/检查点/守卫/拦截），策略全在业务声明（目录/档位/图）；统计与决策分离（FrequencyTracker 只计数，CacheFrequencyPolicy 纯函数）。
5. **评测即真实**：评测跑线上同一条代码路径（同 Runner/同 Assembler/同工具），否则指标是安慰剂。
6. **观测不成为可用性风险**：增强类事件失败只记 debug；推送有界队列丢得起；轨迹独立吞异常。
7. **每一步都可短路、每一次失败都有输出**：决策链每步出口；升级卡带"此前各阶段产出"；取消单独成 OutcomeKind。
8. **用户主权显式化**：显式选择 > 意图识别；#override 确定性协议零模型；来源角标（rule/llm/user_override）透明展示机器补了什么。
9. **自我一致性问题要闭环**：日志反查排除自身 service_name；反思轮跨轮累积防首轮命中被抹；带历史答案缓存旁路防跨会话串答案。
10. **实测驱动**：rerank 分批（分数衰减实测）、改写 AUTO 透传（BM25 字面词）、overlap 分语言（token 密度）、意图阈值 0.8（"疑似"级劫持实证）——每个非常规决策背后有一个数字。

---

## 12. 高频面试 Q&A（按追问深度分层）

### 基础层（必答对）

**Q：为什么手写 JDBC 而不用 Spring AI VectorStore？**
A：三个控制点——集合过滤（metadata->>'collection_id'）、GUC 钉死 HNSW 索引路径（ef_search=200 + enable_seqscan=off，防规划器翻转全表扫描）、嵌入批量控制（百炼批量上限 10）。抽象层拿不到这些控制权。

**Q：RRF 和 Rerank 的分工？**
A：RRF 是无参融合（w/(k+rank)，跨通道位次共识），解决"多通道分数不可比"；Rerank 是交叉编码器精排（query-doc 交互打分），解决"向量召回语义泛、关键词召回字面脆"。融合在精排前收拢候选（candidateLimit=40）。

**Q：Interceptor / Filter / Guard 怎么分？**
A：怎么执行（环绕，可中断）／数据变成什么样（管道，纯变换）／能不能做（谓词，五态决策 Deny/AskApproval/BreakLoop/Transform/Allow）。三者挂载点不同（6/9/14 个 phase），组合出"守卫→过滤→拦截→执行器"的节点执行序。

**Q：会话挂起怎么实现的？**
A：执行器返回 NodeResult.suspended → 引擎保存 checkpoint（ops_engine_session 存整份 SessionRecord 含 Cursor，ops_engine_slots 存 SlotSnapshot）→ 返回 SUSPENDED 不推进游标。恢复时 load 回填 + claim 抢占防并发 + resume **重入同一节点**（幂等：有输入则完成无输入再挂起）。

### 进阶层（讲出门道）

**Q：你们用原生 function calling 吗？为什么？**
A：用过——三代演进：第一代原生手动驱动（internalToolExecutionEnabled=false 自解析 toolCalls），第二代双协议 ModelPort 可切换，第三代（现役）固定 JSON 意图协议。取舍理由：① ask_user/escalate/answer 与 tool 平级为一等意图，控制流统一（原生只能表达"调工具"，控制流得另造机制——旧内核的 finish/ask_user 控制面工具就是为此硬造的，后来删了）；② 协议块随 prompt 三层资产化进指纹，改协议自动失效缓存；③ DeepSeek 遵循率联调验证可靠，解析失败兜底"原文当 ANSWER"。内核保留原生通路（ModelGateway.withTools + toolCalls 派生槽），换 ModelProvider 实现即可切回——架构可切换，业务显式取舍。

**Q：五层缓存为什么不会出现"改了文档还在用旧答案"？**
A：三重保险——入库 bump docver（FAILED 也 bump，双 INCR _all+collection）；docver 拼进 key（旧 key 自然失配，不做 SCAN 删）；TTL 只是回收旋钮。语义缓存还有 paradigm+docver+prompt_hash 三元组精确 WHERE。

**Q：语义缓存 0.95 阈值怎么定的？**
A：防"相似≠同义"："怎么冲红"vs"冲错了怎么撤销"向量距离近但答案不同。宁可少命中不可错重放；命中后回写 exact 层，下问免 embed 直达。

**Q：小块检索大块生成为什么 rerank 之后才聚合？**
A：打分发生在子块级——语义集中、分数区分度高（对照实验期望 +0.103）；交叉编码器吃短文本效果更好；聚合只是折叠席位换回父块原文。同父多子命中只占一席 = 同文档挤占的天然解。

**Q：意图路由为什么设计六层优先级？**
A：从确定性到概率性排序：会话恢复/traceId 正则是确定性判据（零模型），显式选择是用户主权（不该被概率覆盖），意图识别是概率判据所以加置信度门槛（0.8，实测 0.6 会把"疑似"级误判劫持进诊断），默认兜底知识检索。每层短路，成本递增。

**Q：诊断的"一次问齐"怎么做到不挤牙膏？**
A：intake 模块 ask-missing 节点把缺失目录槽一次性列出（目录序 join）；问之前还有 auto-resolve 自救三层（规则→日志反查→LLM 推断）——日志是一手信息源放在 LLM 之前；反查空结果如实交代"已检索：未命中"并摊开关键槽核对入口，而不是编一个。

**Q：Findings 和对话历史什么区别？**
A：历史是"发生了什么"（随窗口滚动），Findings 是"我们主张过什么、哪些还站得住"（ACTIVE/SUPERSEDED/RETRACTED 状态机，Evidence 是指针+摘录防膨胀）。结论四段式使抽取是纯解析零模型；用户点名否认 `[#n]` 先于整体替换执行（顺序颠倒否认会被 SUPERSEDED 吞掉）。

**Q：多轮/跨天的上下文管理是怎么设计的？（上下文架构题）**
A：先讲病根——一个"会话"概念承载三件事（对话容器/一次执行/挂起状态），四个模块各解释一遍"结束了没"。再讲四层结构：Conversation（记忆封闭域）/ Topic（相关性边界）/ Task（工作边界）/ Attempt（执行边界），**每层只回答一个问题**，Task 对 RAG 也是通用层（结构统一策略声明）。核心命题：**上下文是计算出来的不是存出来的**——真相源 append-only → 纯函数投影 → 预算组装 → 组装记录审计；三不变式（计算而非存储/有界而非全量/留痕而非覆盖）；投影五约束（verbatim 保真禁改写/确定性/纯计算无 LLM/失败可区分/版本进指纹）。跨主题必须显式建边不做隐式召回；**永远不带原始消息**。跨会话用"沉淀"（人工确认后提升进知识库）不用记忆泄漏。诚实收尾：Topic 层真机验证后**暂缓**——Task 只按强信号切分，强信号=换实体=换 Topic，会退化成 1:1 空转抽象；先用 related_findings 最小形态顶着，等"同实体内按目标切分"有真实案例再做。这个"用真机数据否决自己设计"的过程本身就是加分项。

### 深挖层（展示工程判断）

**Q：Redis 挂了系统会怎样？**
A：断路器 5 败开路冷却 30s，期间所有缓存调用方零 Redis 调用直接旁路（get 失败=miss 主链路零感知）；docver 返 -1 缓存整体不读不写；同时 DegradeGuard 把 LLM 流式/ops 诊断并发收到 8（缓存保护消失时给下游留活口，礼貌拒绝不排队拖垮）；频率准入进程内化不受影响。半开探测恢复而非瞬间全通。

**Q：rerank 把第一名砍光了怎么办？**
A：这是真实事故：qwen3-rerank 分数随请求总长衰减（12k→0.349，55k→0.208），109 候选一次喂 47k，期望文档 0.28 被 0.3 下限整批误杀。修法是**分批精排**（batch 20，跨批按分合并）；候选不足 topN 仍走精排（顺序与过滤都依赖 API 分数）；失败 fallback 候选前 topN 拷贝。还留了一个设计观点：未精排块不拿来凑数补 topN。

**Q：子 agent / 多 agent 你们怎么做的？**
A：诚实口径——内核有 SubWorkflow/Parallel 节点（BranchInvoker 保证不绕过横切），但业务**没在用**：已知债务是子工作流缺深度限制/环检测/预算总账。当前多 agent 形态是"编排层路由 + 单引擎三 Agent"——路由解决"谁接手"，引擎级会话保证追问链不断。单引擎三 Agent 是刻意的：会话/恢复是引擎级的，双引擎断链。

**Q：你们的评测和真实线上链路一致吗？**
A：三个"同一"（同 Runner/同引擎同工具/同 RagContextAssembler），RunContext.forEval 只钉 forceRetrieval 和 rewrite 开关。曾踩过"评测自己算改写塞 SearchContext，开关接在断线上"——指标全白测。对照实验还要 N 范式同一组 itemIds（各自 shuffle 跑不同子集=对照失真）。

**Q：一次诊断追问隔了一天回来，怎么保证继续而不是重开？**
A：四层表结构分工——sa_agent_task 是工作边界（SUSPENDED 状态 + slots + chainTraceId），ops_engine_session/slots 是执行边界（游标+槽快照）；ResumeCoordinator 优先级最高，SUSPENDED 恒归任务；恢复 load 回填 + claim 抢占；OTel 侧 chainTraceId 用 NonRecordingSpan 强制续链（跨天 trace 不断）；引擎侧记录丢失（跨版本存量）→ 带已确认槽位重开新 attempt 兜底。TaskReaper 60min 兜底回收过期任务。

**Q：如果重新设计，你会改什么？**
A：可讲的真话——① 子工作流的深度/环检测/预算总账会一开始就进内核；② 意图阈值这类"概率路由参数"应该进动态配置而非发版改 yaml；③ registry 的 stages 投影应完全从图派生（现在部分是 controller 静态表）；④ AgentBranchDispatcher 应泛化到任意 workflow 型 agent（现在绑死 OpsRunner）；⑤ 语义缓存可加会话维度让带历史追问也能受益。

### 踩坑故事清单（每条 30 秒，STAR 压缩版）

| # | 故事 | 一句话根因 |
|---|---|---|
| 1 | 评测 expected_doc_ids 全 0 | Indexer metadata 写了 taskId 而非 docId（task 与 doc 双身份分离） |
| 2 | 反思轮空手抹掉首轮命中 | TOOL 节点派生槽每轮覆盖写 → 改跨轮累积槽 kb_chunks_all |
| 3 | rerank 第 1 名整批被砍 | 分数随请求总长衰减 → 分批精排 |
| 4 | 检索全表扫描变慢 | ef=200 代价估算翻转规划器 → 三 GUC 钉死索引 |
| 5 | "疑似"级 0.6 置信度劫持诊断 | 追问被判 ops 域 → 阈值收紧 0.8 |
| 6 | 日志反查自我循环 | prompt 文本含"请求报文"被当业务日志读回 → service_name 排除 |
| 7 | 一次问答断成数截 trace | 虚拟线程不继承线程本地 → ContextSnapshot/TaskPropagation 搬运 |
| 8 | 追问轮 trace 不续链 | ambient 父优先于 chainTraceId → NonRecordingSpan 强制挂链 |
| 9 | 395MB 评测表 89% 冗余 | 轨迹随每指标行复制 → 拆 sa_eval_item_trace |
| 10 | LiveRAG 62 冗余副本 | 支撑文档不按 urn 共享 → computeIfAbsent 跨题去重 |
| 11 | HNSW 查询 7.6s 撞 8s 超时整通道挂 | 超时是"通道存亡开关" → 30s |
| 12 | 每问泄漏一行引擎会话、ephemeral 从未生效 | checkpoint 绕过 ephemeral 检查直写 store → 判定接进 PersistenceManager（单测全绿、数库行数才暴露） |
| 13 | react 轴指标恒 0 | 出口只认 Map 形状，react 是 List → 兼容两种形状 |
| 14 | 工具参数漂移白烧一轮循环 | 协议行手写 topK → 从 schema 渲染 |
| 15 | 归集后检索 0 召回 | 只改 sa_document 不改向量 metadata → jsonb_set 同步 |
| 16 | 表达式 slots.x>0 恒真 | 字符串比较兜底 → 分支判定用 ==1 |
| 17 | 旧库部署缺列启动崩 | init.sql 自迁移只补 ALTER 列 → ensure-schema.sql 239 条幂等补丁 |
| 18 | 跨页代码被切断 | 净计数分不清"开了没闭"→ 栈扫描 bracketStack |
| 19 | CONCLUDED 任务追问被 RAG 劫持 | findResumable 只认 SUSPENDED → CONCLUDED 默认归任务+换目标强信号例外；教训：单测验证不了"组装起来是不是对的事" |
| 20 | 追问永远开不出第 2 个 attempt | markSuspended 照抄执行前旧 state 冲掉 beginAttempt 递增 → javadoc 写死禁写 attempt_count |
| 21 | 换目标后旧槽位永远占位 | 无条件灌 task.slots + 抽槽已确认值优先 → STALE_ON_TARGET_SHIFT 清五槽 + ResumeCoordinator 委托同一判据 |
| 22 | 存储大户误判 | 以为 sa_message 要冷热分层，实测 1.1MB/年；真凶是孤儿引擎槽位 76MB → TaskReaper 孤儿回收阶段 |

---

## 附：按面试方向的侧重速查

| 岗位方向 | 主讲章节 | 一句话主打 |
|---|---|---|
| Agent 框架工程师 | 4 + 5 + 6 | 自研图范式内核：三层横切/游标主循环/挂起恢复/HITL 四形态 |
| RAG / 算法工程 | 2 + 3 + 9 | 分块-检索-融合-重排-生成全链 + 小块大块 + 评测闭环 |
| 后端 / 架构 | 1 + 7 + 8 | 16 模块分层/SPI/决策链/缓存熔断降级铁三角 |
| 平台 / DevOps | 8 + 10 + 踩坑清单 | 稳定性设计 + 全链路可观测 + 实战事故 |
| 全栈 | + delivery/前端 | SSE 五事件协议、clarify 卡片落库重渲染、对照面板 |

> 最后提醒：所有数字（阈值/配置/指标）都以本文档为准（2026-09-22 代码核查），
> 与旧 CLAUDE.md / plan 文档冲突处以本文档为准；面试前建议跑一遍 `/agent/registry` 和一次完整问答再对对口径。



---

<!-- 原文件：docs/resume-agent.md -->

## 附：个人简历模板（AI Agent / 大模型应用工程师）


## 基本信息

| 项 | 内容 |
|---|---|
| 姓名 | 〔填写〕 |
| 电话 / 邮箱 | 〔填写〕 |
| 城市 / 求职状态 | 〔填写〕 |
| GitHub / 博客 | 〔填写〕 |
| 学历 / 院校 / 专业 | 〔填写〕 |

## 求职意向

AI Agent 工程师 / 大模型应用工程师（Agent 框架、RAG 平台、LLM 工程化方向）。

## 技能概览

- **语言与框架**：Java 21（主力）、Spring Boot 3.5、Spring AI 1.1、Vue 3 + TypeScript、Python（评测与脚本）、Maven 多模块
- **Agent 工程**：Agent/Workflow 分层执行框架、原生 function calling 与手写工具循环、工具协议与 JSON Schema 护栏、多 Agent 嵌套调用（预算 / 深度 / 环检测）、会话与槽位填充（slot filling）、意图路由、Prompt 分层装配与版本化、能力位（capability）治理
- **RAG**：多通道检索（pgvector 向量 + BM25/SQL 关键词）、去重与 RRF 融合、Rerank 精排、结构感知分块、多格式解析（Tika / Markdown / CSV / MinerU）、VLM 图片理解、检索评测（Recall@k / Precision@k / MRR / nDCG）
- **LLM 工程化**：SSE 流式输出、多级缓存（Redis + pgvector 语义缓存）、熔断降级与动态限流、评测闭环（Langfuse dataset run）、成本与轨迹可观测
- **可观测**：Micrometer + OpenTelemetry、OpenObserve、Langfuse、traceId 全链路贯通；自研 llm-telemetry 组件（JitPack 发布）
- **基础设施**：PostgreSQL 16 + pgvector + pg_search、Redis 7、RocketMQ / Redis Stream、S3 兼容对象存储（MinIO / RustFS）、Docker Compose

## 项目经历

### 企业级 AI Agent 平台（Agent 框架 + RAG 引擎）｜核心开发 / 框架设计

**时间**：〔填写〕 ｜ **技术栈**：Java 21 · Spring Boot 3.5 · Spring AI 1.1 · PostgreSQL/pgvector · Redis · RocketMQ · Vue 3 + TypeScript

**项目简介**：面向企业知识问答与运维排障场景的 AI 平台。既要高质量 RAG（多格式入库、多通道检索、精排、流式回答），又要可扩展的 Agent 编排能力（多 Agent、多流程、工具调用、澄清追问、升级转人工）。项目从单体 `app/` 重构为 15 个 Maven 子模块的分层架构，Java 源文件约 460 个、代码约 3.3 万行（含测试），端到端 193 个测试用例全绿。

#### 1. 设计并落地可复用的 Agent 执行框架（零业务依赖，可独立发布）

- 抽离独立 `agent-framework` 组件：`Agent`（身份 + 能力声明 + 绑定流程）与 `Workflow`（声明式阶段序列）是两棵互不影响的继承树，靠"绑定"组合，避免"一种业务一个 Agent 子类"的继承爆炸；
- 定义三种节点形态：`LOOP`（模型在工具白名单内自主多步决策）、`DETERMINISTIC`（零 LLM 决策的确定性调用）、`AGENT_CALL`（子 Agent 重入同一执行内核），并开放 `NodeExecutor` 扩展点，让新节点形态自动获得引擎托管的预算、错误策略、观测与护栏；
- 工具二分收口：`BaseTool`（finish / ask_user / escalate 控制面，引擎硬注入、不受白名单管控）+ `ExtensionTool`（检索、日志查询、参数校验等数据面，注册表统一收集、按阶段白名单开放），从协议层面杜绝业务工具干扰循环状态；
- 子 Agent 治理：调用深度上限 + 环检测（A→B→A 拒绝）+ 预算总账（父可切分上限但不可被绕过）+ trace 嵌套 span + 引用编号上卷重映射，让多 Agent 组合既灵活又可观测、可追溯；
- 能力位三方模型：生效能力 = Agent 声明 ∩ Workflow 供给 ∩ 配置开关，且"配置只能收窄、流程只能供给、交集不足在装配期直接报错"，杜绝静默降级导致的线上语义漂移；
- 架构约束用测试守护：分层依赖方向、包结构归属、SPI 边界均由测试断言，框架在编译期保持零业务依赖，业务侧升级框架只需换依赖版本。

#### 2. 统一执行出口与流式交付协议

- 设计 `ExecutionResult` 统一出口（内容 + 上下文证据 + 引用索引 + 生成规格 + 执行指纹 + 检索统计 + 执行轨迹）与 `ExecutionMetadataSink` 执行中回调，形成"结果携带 + 过程回调"双通道，解决"引用映射与执行指纹必须先于流式生成就绪"的时序难题，使引用溯源、缓存 key 与评测复现共用同一份事实；
- 落地 SSE 事件协议 `message / trace / citations / clarify / meta`，用 traceId 把"消息 → Agent 轨迹 → 全链路 span"三方关联，前端可一键从回答跳到轨迹与链路视图；
- 事件降级设计：增强类事件（trace / citations / meta）发送失败只记 debug 日志、绝不阻断回答主链路，观测能力不成为可用性风险。

#### 3. 对话编排与多通道检索

- 实现 `ChatOrchestrator` 决策链：会话恢复 → 查询归一化 → 规则短路 → 用户显式范式 → 指代悬空澄清 → 意图分类 → 规则路由 → 分支（闲聊直答 / 运维诊断 / RAG 主线），每一步都设短路出口，把"用户想问什么"与"由谁来答"彻底解耦；
- 意图路由按优先级两轮求值（预处理短路类规则先行、意图域规则后行），支持 traceId 直接跳诊断、低置信度回落知识检索，避免误判把用户带进错误流程；
- 多通道检索链路：pgvector（HNSW + cosine）向量召回 + 关键词召回（SQL / pg_search BM25）→ 去重 → RRF 融合 → qwen3-rerank 精排 → 后处理（文档多样性、同源扩展、票据类型过滤），并支持三级检索预算（召回 / 候选 / 上下文 topK）与通道超时隔离；
- 入库流水线：Tika / Markdown / CSV / MinerU 多格式解析 → 统一 Block 模型（标题 / 段落 / 表格 / 代码 / 图片 / 列表）→ 结构感知分块 → LLM 增强与富化 → VLM 图片描述 → pgvector 入库；节点式引擎 + 条件求值 + 节点级日志落库，消息队列可在 RocketMQ 与 Redis Stream 之间切换。

#### 4. 缓存、降级与稳定性

- 五层 Redis 缓存（意图 / 检索结果 / 日志工具 / 精确答案 / 查询 embedding）叠加 pgvector 语义答案缓存（相似度 ≥ 0.95 命中重放），并支持配置级开关与 TTL；
- 缓存 key 设计：SHA-256 截断 24 位十六进制、以不可见字符分隔各段避免拼接歧义；检索 key 强制包含 `SearchContext` 全参数与文档版本号，保证评测"多参数扫描同引擎跑不同参数"时结果不会串味；
- 失效策略：文档版本号（docver）递增使旧 key 自然失效，改 Prompt / 换范式因指纹进入 key 而自动失效，不做 keys 全量扫描；
- 频率准入与热度分层：进程内 LeapArray 风格 16 桶环形时间窗（O(1) CAS 计数、求和 O(16)），以"窗口内重复出现"作为写入门槛，命中且热度达标延长 TTL——既挡住长尾一次性请求污染缓存，又避免每请求多次 Redis RTT，降级期准入判定照常可用；
- 稳定性联动：RedisHealth 熔断器（连续失败开路 → 冷却 → 半开探测恢复）与 `DegradeGuard` 动态限流（降级期仅对昂贵路径收紧并发，健康期零成本直通）配合，被拒绝的请求返回礼仪式 SSE 提示，而不是排队拖垮整个服务。

#### 5. 评测与可观测闭环

- 搭建评测框架（样本模型 / 打分器 SPI / 结果落盘三件套）：检索侧 Recall@k、Precision@k、MRR、nDCG，上下文侧召回率与精确率，答案侧 LLM Judge；支持评测集导入（LiveRAG）与多范式对照跑批；
- 评测结果作为 Langfuse dataset run 推送，指标按 traceId + datasetRunId 关联，可在 UI 上跨 run 对比，形成"改检索 / 改 Prompt → 跑批 → 对比 → 回归"的闭环；
- 全链路可观测：基于自研 llm-telemetry 组件（已发布 JitPack 坐标）做 Micrometer + OpenTelemetry 埋点，双后端输出 OpenObserve 与 Langfuse；LLM 的 gen_ai span 与 token 用量复用 Spring AI 原生观测；
- 前端（Vue 3 + TypeScript + Ant Design Vue）实现对话、Agent 能力清单、Agent 轨迹树、链路查看、缓存看板、评测看板与多范式对照页，把上面的能力变成可视、可操作的产品界面。

#### 6. 工程化与协作

- 主导 15 模块重构：从单体 `app/` 拆分为框架层 / 基础设施层 / 能力层 / 业务层四层，业务域之间只经 shared 中的 SPI 通信，交付通道（SSE）、会话存储、文档目录全部接口化，模块依赖方向由测试固化；
- 提交安全：pre-commit 钩子扫描真实 IP / 账号 / 密码 / API Key，示例统一使用占位符，真实值只进 `.env`；
- 本地一键部署：`docker compose` 拉起 PostgreSQL / Redis / RustFS / OpenObserve / OTel Collector 与应用，降低协作与演示成本。

**项目成果**：〔填写真实数据，例如：累计入库文档 X 篇 / 覆盖 X 个业务知识库；日均问答 X 次；缓存命中率 X%；P95 首字延迟从 X ms 降到 X ms；评测 Recall@5 从 X 提升到 X；运维排障平均处理时长从 X 降到 X〕

**我的角色**：〔填写，例如：Agent 框架设计与内核实现、RAG 检索链路、缓存与降级、评测框架；团队 X 人，负责 X 部分〕

## 开源 / 其他项目

- **llm-telemetry / llm-observability**（作者）：LLM 全链路观测组件，提供注解式埋点（steps / trace）、上下文传播、Spring AI 内容捕获与 OpenObserve / Langfuse 后端适配，已发布 Maven 坐标并被上述平台复用。
  - 仓库：〔填写〕

## 教育背景

〔院校 · 专业 · 学历 · 起止时间〕

## 面试可展开的技术点

- Agent 框架为什么拆成 Agent / Workflow / Tool / Engine 四类构件，各自边界与禁止项
- 子 Agent 嵌套的预算、深度、环检测与 trace 上卷实现
- 为什么"引用映射与执行指纹必须先于流式生成就绪"，以及双通道元数据契约如何解决
- 缓存 key 的构造与失效策略（docver + promptHash + 全参数 SearchContext）
- LeapArray 频率统计与 Redis INCR 方案的取舍，熔断与限流的联动
- RRF 融合与 Rerank 的分工，多通道召回的预算控制
- 评测指标口径与 Langfuse dataset run 的对接方式

---

> 使用说明：所有 〔〕处需要按真实情况填写，尤其是**项目成果**与**我的角色**——面试官最关注这两块。若投递岗位偏 Agent 框架，可把第 1、2、4 节提前；若偏算法 / RAG，可把第 3、5 节提前并压缩工程化内容。
