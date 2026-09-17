# 个人简历 · AI Agent / 大模型应用工程师

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
