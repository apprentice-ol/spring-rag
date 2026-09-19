# 模块化拆分与分层重构进度（2026-09-12 完成）

> 跨会话交接文档：新会话读这一份即可掌握全貌，无需历史对话。
> 结构权威文档：`docs/project-structure.md`；功能记录：`docs/agent-rebuild-spec.md`。

## 1. 结论：三层拆分 + 编排回归业务层，全部完成

| 步骤 | 模块 | 状态 |
|---|---|---|
| 0 | `platform-shared`（配置 + Prompt 加载 + 模型/HTTP 装配 + 跨域 SPI 契约） | ✅ |
| 1 | `platform-observe`（OpenObserve / Langfuse / TraceSampling） | ✅ |
| 2 | `platform-knowledge`（检索/重排/后处理 + 检索感知缓存 + RAG 生成） | ✅ |
| 3 | `platform-prompt`（资产/版本/能力包 + prompts 资源） | ✅ |
| 4 | `platform-storage`（S3 + 文件元数据）、`platform-ingestion`（解析/分块/增强/富化 + 文档目录） | ✅ |
| 5 | `platform-business`（**业务/调度层**：orchestration / agents / workflows / tools / routing / runtime / session / trace / rest） | ✅ |
| 6 | `platform-delivery`（**交付通道层**：sse / message / runtime / service / rest） | ✅ |
| 7 | `platform-eval`（数据集/跑批/指标/对照/推送） | ✅ |
| 8 | `platform-mcp`（MCP 扩展工具来源）、`platform-console`（控制台视图）、`platform-bootstrap`（唯一可执行装配点） | ✅ |

验证：`mvn -o -s .mvn-settings.xml clean test` → **16 个模块 BUILD SUCCESS，180 测试全绿，0 失败 0 错误**。

## 2. 拆分过程中修正的真实架构问题（已全部解决）

| # | 问题 | 修正 |
|---|---|---|
| 1 | 共享配置（ChatProperties 等）留在业务模块 → 任何域抽取都成环 | 建 `platform-shared` |
| 2 | `cache ↔ knowledge` 环（缓存装饰器/语义缓存依赖检索类型） | 检索感知类归 knowledge，cache 变纯基础设施 |
| 3 | `agent → chat` 环（路由规则与意图类型在 chat） | 路由契约（`platform.routing`）与 `IntentResult`（`platform.intent`）下沉 shared |
| 4 | 通用 DTO / 类型处理器散落业务域（`PageResult`、`JsonbTypeHandler`） | 提到 `platform-common.dto` / `platform-common.mybatis` |
| 5 | 资源未随域走（`prompts/**`、`agent/schemas/**`）导致跨模块加载失败 | 资源归位到 platform-prompt / platform-business |
| 6 | 跨模块测试共享（`FakeCacheStore`） | `platform-cache` 产出 test-jar，按 test scope 依赖 |
| 7 | `TelemetryDimensions` 依赖 agent 领域类型却放 observe（会成环） | 归 `platform-business`（本就是智能体感知维度） |
| 8 | 依赖缺失（okhttp / pgvector / commonmark / awssdk / redisson-starter / tika / micrometer-*） | 各模块 pom 显式声明 |
| 9 | 交付副作用（SSE/落库/引用）散在编排代码里 | 抽 `DeliveryPort` SPI，实现收口到 `delivery.sse`；编排层零传输细节 |
| 10 | 编排决策段留在 delivery → 调度逻辑被"通道"包住 | 抽 `business.orchestration.ChatOrchestrator`，delivery 退化为入口薄壳 + 端口装配 |

## 3. 当前依赖链（无环）

```
platform-bootstrap → {business, delivery, eval, console, ingestion, knowledge, prompt, observe, cache, shared, common, mcp, framework-*}
platform-business  → {knowledge, prompt, observe, cache, shared, common, mcp, framework-*}
platform-delivery  → {business, knowledge, prompt, ingestion, observe, cache, shared, common, framework-*}
platform-eval      → {delivery, business, knowledge, ingestion, observe, cache, shared, common, framework-*}
platform-ingestion → {knowledge, storage, prompt, observe, cache, shared, common}
platform-knowledge → {cache, observe, prompt, shared, common}
platform-prompt    → {shared, common, framework-*}
observe / storage / cache → {shared, common}
shared → common
```

跨域只经 shared 的 SPI（见 §5），不存在业务域之间的实现依赖。

## 4. 常用命令

```bash
export JAVA_HOME=D:/03_developtools/02_jdk/ms-21.0.11   # 必须用该 JDK
mvn -o -s .mvn-settings.xml clean test                   # 全量构建（16 模块 / 180 测试）
mvn -o -s .mvn-settings.xml -pl platform-business -am test   # 单模块 + 依赖
```

## 5. 跨域 SPI 一览（接口在 platform-shared，实现在能力/交付模块）

| SPI | 作用 | 实现 | 备注 |
|---|---|---|---|
| `platform.delivery.DeliveryPort` | 交付通道：事件（delta/direct/clarify/escalate/citations/trace/notice/degraded/cachedReplay/complete）+ 助手消息落库 + 流交付 `emitStream(StreamSpec)` | `delivery.sse.SseDeliveryPort` | 一次请求一实例，绑定传输载体 |
| `platform.delivery.DeliveryPortFactory<S>` | 取端口（普通 / 流式），泛型 `S` = 传输载体（编排层不透明） | `delivery.sse.SseDeliveryPortFactory`（`S = SseEmitter`） | 泛型装配由 `ChatOrchestratorWiringTest` 守护 |
| `platform.conversation.ConversationStore` | 会话事实：ensureConversation / appendUserMessage / historyContext / userMessageCount / recentUserText | `delivery.message.ChatMessageWriter` | 编排层不接触 sa_* 表 |
| `platform.document.DocumentCatalog` | 文档目录：allDocumentNames / sourceLocations | `ingestion.catalog.IngestionDocumentCatalog` | 指代悬空判定与引用原文链接 |
| `platform.routing.*` / `platform.intent.IntentResult` / `platform.clarify.*` | 路由表、意图、澄清契约 | business 侧规则与实现 | 中立契约，business 与 eval 共用 |

## 6. 编排层边界（判定标准）

`business.orchestration.ChatOrchestrator` 内**不得出现**：`SseEmitter`、`SseEventSender`、`MessageMapper`、
`SseDeliveryPort*`、`DegradeGuard` 之外的交付细节；只允许 shared SPI + 业务/能力域契约。

```
POST /chat/stream
 └─ delivery.rest.ChatController → delivery.service.ChatService → business.orchestration.ChatOrchestrator
     ├─ 决策：会话恢复 → 归一化 → 规则短路 → 显式范式 → 指代悬空 → 意图 → 规则路由 → 分支
     ├─ RAG：ConversationStore.historyContext → QueryRewriter → FrameworkKnowledgeRunner（agent+workflow）
     │      → knowledge.retrieval（多通道 + 两级缓存）→ KnowledgeAnswerService（流式生成）
     │      → orchestration.rag.RagContextAssembler（上下文 + 引用映射）
     └─ 交付：DeliveryPortFactory → delivery.sse.SseDeliveryPort → SseEventSender + ChatMessageWriter
```

## 7. 后续可选优化（非阻塞）

1. `ChatOrchestrator` 仍是单体类（~700 行，含 8 个分支）；如需再拆，按"分支 → 独立 Handler"演进（编排骨架不动）。
2. `RagContextAssembler` 现居 `business.orchestration.rag`（在线编排与 eval 共用）；若将来把文档目录查询下沉到 knowledge，可随检索域再迁移。
3. 若要抽独立坐标发布：`platform-agent-framework-*` 已零业务依赖，可优先独立成仓。
4. `DeliveryPort` 的 `onSuccessExtra` / `onSubscribed` 目前无调用方（预留钩子），可评估删除。
