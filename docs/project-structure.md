# 项目结构与命名规范（2026-09-12 定稿）

## 1. 总体结构：16 个 Maven 模块，单向成层

```
customer-platform（root pom，groupId com.jjx.customer，包根 com.jjx.customer.platform）
│
├── 框架层（可复用，零业务依赖）
│   └── platform-agent-core                内核（包根 com.agentframework，自 agent-framework 仓库整包并入，见
│                                          docs/agent-rebuild-spec.md §9）：engine（引擎/构建器/执行器 SPI）/
│                                          definition（workflow/node/agent/tool/region 声明）/ crosscutting（cache/guard/
│                                          filter/interceptor/trace/metrics）/ runtime（session/slot/event/persistence）/
│                                          infra（modelgateway/storage）/ extension / sdk
│
├── 基础设施层
│   ├── platform-common                    通用：util / mybatis 类型处理器 / 分页 DTO（最底层，无域依赖）
│   └── platform-shared                    共享：配置属性 / Prompt 加载 / 模型与 OkHttp 装配 / VlmClient +
│                                          跨域 SPI 契约：delivery · conversation · document · routing · intent · clarify
│
├── 能力域层
│   ├── platform-cache                     缓存：CacheStore / Redis 装配 / 频率准入 / 语义缓存基础设施
│   ├── platform-observe                   观测：OpenObserve / Langfuse / TraceSampling 装配
│   ├── platform-storage                   存储：S3 对象存储 + 文件元数据
│   ├── platform-knowledge                 知识：多通道检索 / 融合 / 重排 / 后处理 + RAG 生成（KnowledgeAnswerService）
│   ├── platform-prompt                    Prompt：资产化 / 版本 / 能力包与绑定 / prompts 资源
│   └── platform-ingestion                 入库：解析 / 分块 / 增强 / 富化 + 文档目录（DocumentCatalog 实现）
│
├── 业务层（调度 + 交付）
│   ├── platform-business                  业务/调度（2026-09-19 重排，RAG 线与诊断线顶层分域）：
│   │                                      engine/（agent-core 桥接：AgentEngineConfiguration 装配、AgentCatalog、
│   │                                        adapter/persistence/outcome 子包）
│   │                                      workflow/common/（两域共用节点执行器：ActExecutor、EscalateExecutor、EscalateTerminal）
│   │                                      knowledge/（RAG 线全部：KnowledgeRunner、workflow/ 双图工厂、node/ 七个执行器、
│   │                                        intent/、normalize/、rag/）
│   │                                      ops/（诊断线全部：OpsRunner、人在环中（HumanResponseInterpreter 回复解释器 /
│   │                                        AutonomyLevel+AutonomyPolicy 自主档位）、workflow/ 图工厂+stages、
│   │                                        node/、slot/（SlotProvenance 槽位来源）、tool/、rest/）
│   │                                      orchestration/（ChatOrchestrator 决策骨架 + 8 个协作类，纯跨域调度）/
│   │                                      routing / runtime（DegradeGuard）/ session / trace / telemetry / agent/schemas 资源
│   ├── platform-delivery                  交付通道：sse（端口实现 + 事件协议）/ message（会话消息持久化）/
│   │                                      runtime（活动流注册表）/ service（入口薄壳）/ rest
│   ├── platform-mcp                       MCP 接入：外部扩展工具来源
│   └── platform-eval                      评测：数据集 / 跑批 / 指标 / 对照 / 外部推送
│
└── 收口
    ├── platform-console                   控制台视图 / 全局异常 / ping / AgentRegistry
    └── platform-bootstrap                 唯一可执行装配点：启动类 / application*.yaml / sql / static / OpenApi
```

### 一次 RAG 请求的调用链（先看这条链，再看模块）

```
POST /chat/stream
 └─ delivery.rest.ChatController                      通道入口（SSE 载体）
     └─ delivery.service.ChatService → business.orchestration.ChatOrchestrator.execute(…, sink)
         ├─ 决策链：会话恢复 → 归一化 → 规则短路 → 显式范式 → 指代悬空 → 意图分类 → 规则路由
         │   （决策骨架在 ChatOrchestrator；各步实现体在 orchestration/ 的 8 个协作类）
         ├─ ops 支路：framework agent + workflow（ops.OpsRunner，经 AgentBranchDispatcher 分派）→ Outcome 分派（追问/直答/升级）
         └─ RAG 主线：
             ├─ conversation.ConversationStore.historyContext（会话事实）
             ├─ knowledge.normalize.QueryRewriter（LLM 改写）
             ├─ knowledge.KnowledgeRunner（agent + workflow；检索 = extension tool，执行权在框架引擎）
             │   ├─ knowledge.retrieval（多通道检索 / 重排 / 后处理 + 两级答案缓存）
             │   └─ knowledge.answer.KnowledgeAnswerService（流式生成）
             ├─ knowledge.rag.RagContextAssembler（上下文文本 + 引用溯源映射）
             └─ delivery.DeliveryPortFactory → delivery.sse.SseDeliveryPort
                 ├─ sse.SseEventSender（message / trace / citations / clarify / meta 事件协议）
                 └─ message.ChatMessageWriter（sa_conversation / sa_message 读写）
```

### 跨域 SPI 一览（接口在 shared，实现在能力/交付模块）

| SPI（platform-shared） | 作用 | 实现 |
|---|---|---|
| `platform.delivery.DeliveryPort` / `DeliveryPortFactory<S>` | 交付通道（事件、落库、流生命周期） | `delivery.sse.SseDeliveryPort` / `SseDeliveryPortFactory` |
| `platform.conversation.ConversationStore` | 会话事实读写（历史、先行对象） | `delivery.message.ChatMessageWriter` |
| `platform.document.DocumentCatalog` | 文档目录（文档名、原文链接） | `ingestion.catalog.IngestionDocumentCatalog` |
| `platform.routing.RouteRegistry` / `RouteRule`、`platform.intent.IntentResult`、`platform.clarify.*` | 路由 / 意图 / 澄清契约 | business 侧规则与实现 |

### 依赖方向（硬约束）

```
bootstrap → 业务域（business / delivery / eval）→ 能力域（knowledge / prompt / observe / cache / storage / ingestion）
          → platform-shared → platform-common
business → knowledge（RAG 能力）；delivery → business（编排入口）；eval → business / delivery（跑批与对照）
业务域之间不直接 import 具体实现；跨域只经上表 SPI 或 shared 中立契约
唯一汇总点：platform-bootstrap（只有它允许依赖全部模块）
```

| 规则 | 说明 |
|---|---|
| 框架零业务依赖 | `platform-agent-core`（com.agentframework）不含任何 `platform-*` 业务模块依赖 |
| 编排层不碰传输 | `ChatOrchestrator` 内不出现 `SseEmitter` / `SseEventSender` / `MessageMapper`（只依赖 shared SPI） |
| 交付层不做决策 | `platform-delivery` 不判断意图/路由/检索，只执行"怎么送到用户" |
| 路由/意图契约中立 | `platform.routing`、`platform.intent` 在 shared，business 与 eval 都依赖它，避免环 |
| 检索感知的缓存类归 knowledge | `CacheKeys` / `CachingRetrievalEngine` / `SemanticAnswerCache` / `DocumentVersionStamp` |
| 资源随域走 | `prompts/**` 在 platform-prompt；`agent/schemas/**` 在 platform-business |
| 跨模块测试复用 | `platform-cache` 产出 test-jar（`FakeCacheStore`），按 `test` scope 依赖 |

## 2. 包与命名规范

### 2.1 包名

- 根包：`com.jjx.customer.platform`；框架：`com.agentframework`（platform-agent-core，刻意与业务包根分离）。
- 全小写、单数、领域名词；禁用缩写暗号（`fw`、`mgr`、`util2`）。
- 分层后缀固定：`orchestration`（编排决策）/ `domain`（数据）/ `runtime`（装配与运行）/ `session|trace|message`（持久化）/ `rest`（对外接口）/ `sse`（SSE 通道细节）。

### 2.2 类名后缀

| 后缀 | 含义 | 示例 |
|---|---|---|
| `Agent` / `Workflow` | 框架契约实现 | `AgentDefinition`、`WorkflowDefinition`（agent-core）|
| `Tool` | 工具能力（单一内核契约） | `RetrievalTool`、`QueryLogsTool` |
| `Rule` / `Strategy` | 路由 | `TraceIdShortCircuitRule`、`OpsIntentRouteRule` |
| `Runner` | 一次执行的适配器 | `KnowledgeRunner`、`OpsRunner` |
| `Orchestrator` | 一次请求的编排决策 | `ChatOrchestrator` |
| `Port` / `Store` / `Catalog` | 跨域 SPI 契约（shared） | `DeliveryPort`、`ConversationStore`、`DocumentCatalog` |
| `Controller` / `Service(+Impl)` / `Mapper` / `Entity` | 分层构件 | `ChatController`、`AgentTraceServiceImpl` |
| `Config` / `Properties` | 装配与配置 | `RedissonConfig`、`ChatProperties` |
| `Request` / `Response` | 传输对象 | `DiagnoseResponse` |

### 2.3 配置键例外

`rag.chat.agent.*` 等**配置键保持不变**（@ConfigurationProperties / @Value / yaml 同步），
不随包名或模块名调整，避免破坏线上配置与环境变量。

## 3. 构建与验证

```bash
export JAVA_HOME=D:/03_developtools/02_jdk/ms-21.0.11   # 必须用该 JDK（Temurin 21.0.11 在本机 javac 会致命错误）
mvn -o -s .mvn-settings.xml clean test
```

判定标准：**16 个模块 BUILD SUCCESS，测试全绿**（分模块测试数见各模块 `target/surefire-reports`）。
