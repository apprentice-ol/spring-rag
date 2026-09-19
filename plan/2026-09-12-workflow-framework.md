# Workflow 框架化执行计划（2026-09-12，v2 重写版）

> **执行状态（2026-09-13 刷新）**：本计划已全部落地——框架抽为同仓 `platform-agent-framework`
> （当日后续变更：core / engine 两模块**合并为单模块**，包按概念分组，无 core/engine 层级；
> 下文 `Workfolw*` 为当时拼写，现名 `WorkflowStageSpec` / `WorkflowErrorPolicy`）
> （零业务依赖），业务侧 Agent/Workflow 定义在 `platform-business`，编排决策在
> `business.orchestration.ChatOrchestrator`，交付通道在 `platform-delivery`（经 shared 的 `DeliveryPort`/`DeliveryPortFactory` SPI）。
> 下文复选框保留为当时的执行记录；当前结构与判定标准见 `docs/project-structure.md` 与
> `plan/2026-09-12-module-split-progress.md`。

## 背景与设计哲学

三轮架构讨论（2026-09-12）的结论：

1. **概念错位**：`Agent` 一词跨层指代两种粒度——顶层"范式/领域处理器"（整条链）与期望的
   "步骤关节"。隐喻（Workflow=骨架、Agent=关节）在 pipeline→范式 Agent 层成立，
   在 WorkflowEngine 内部不成立（关节是匿名的 ToolLoop + 阶段prompt + 白名单组合）。
2. **演进方向**：保留"Agent 持有 Workflow"组合，但把 Workflow 从 OpsDiagnoseAgent 的私有
   执行机制提升为**平台级框架**——任务声明为 workflow 即自动获得公共横切能力
   （错误处理/观测性/数据校验/预算），保留声明差异与行为钩子两级自定义。
3. **意图路由泛化**：意图识别后选用不同 workflow；新增 workflow 时 pipeline/引擎零改。

**v2 修订（用户决策，2026-09-12）：无历史包袱约束。**

- 不为旧实现形状妥协——契约按理想形态一次定义到位，旧代码服从新契约（重写可接受）；
- **历史升级而非迁就**：代码兼容层删除（见删除清单）；行为按新契约定义，新增横切能力
  导致的行为变化是**升级不是回归**，验收不以"逐分支等价"为准；
- 零成本纯数据容错保留（`RagParadigm.LEGACY_ALIAS` 服务 eval 历史 run 记录，静态 Map 无代码路径负担）。

## 三清单：保留 / 重写 / 删除

### 保留（现有设计本来就对，重写无收益只有风险）

| 组件 | 理由 |
|---|---|
| `ToolLoopEngine` + `LoopSpec` + `JsonToolCallAdapter` + `LoopExitChecks` | 关节内核，NATIVE/JSON 双协议完整，09-11 刚分层 |
| `SlotSpec`/`SlotEvaluator`/`SlotMerger`/`SlotExtractor` | 声明式槽位机制，纯函数 |
| `ReplanChecker`（prompt 经 definition 注入） | 骨架零业务的关键模式 |
| `AgentSessionService`（AWAITING_USER 持久化 + TTL） | 跨轮次会话恢复 |
| `AgentTrace`/`AgentStep`/`AgentStepDetails`、trace/session 持久化域 | 轨迹契约与域内聚 |
| `AgentTool`/`ToolRegistry`/`ToolProvider` 工具体系（含 mcp/） | 09-11 刚完成工具分层 |
| `ArchitectureFitnessTest` | 分层守护，规则随本次包结构调整同步更新 |
| `RagParadigm` + `LEGACY_ALIAS` | 刻意注册点 + 存量数据容错 |

### 重写（新契约一次到位）

| 组件 | 重写内容 |
|---|---|
| `WorkfolwStageSpec` | 新字段：`when` / `workfolwErrorPolicy` / `outputGuardSchema`（见设计契约） |
| `WorkflowDefinition` | 加 workflow 级预算覆盖位（`maxLlmCalls`/`timeoutSeconds`，<0=用全局默认） |
| `WorkflowAgent` | 4 个行为钩子（default 方法） |
| `WorkflowEngine` | 横切能力内建：when 求值、workfolwErrorPolicy 分派、预算检查、自动观测、outputGuard |
| `OpsStageCatalog` + `OpsDiagnoseAgent` | 按新契约重装配；Catalog 收敛为纯阶段目录（覆盖机制重设计，见决策记录） |
| `ChatOrchestrator` 路由段 | if-else 链 → 规则表求值 |
| `IntentResult` + `DefaultIntentClassifier` | 去域感知（domain 化，prompt 域清单动态拼装） |
| `Agent` SPI + `AgentTask` | 双形态显式化：`RetrievalAgent` / `WorkflowAgent`；AgentTask 拆公共+形态参数 |
| `AgentProperties.StageConfig` | 从"完整阶段镜像"降级为"参数覆盖子集"（maxSteps/replanAfter/workfolwErrorPolicy） |

### 删除（历史兼容层）

| 删除项 | 替代 |
|---|---|
| `core/legacy/AgentRetrievalAdapter` | 三消费点直接吃 `AgentOutcome`（见批次 2 #2.4） |
| `core/legacy/AgentRetrievalResult` | `AgentOutcome.finalChunks()` + `isEmpty()`（verdict=EMPTY 本质即 chunks 空） |
| `core/legacy/RetrievalVerdict` | 同上；`TelemetryDimensions` 维度提取挂到 `AgentOutcome` |
| `StageConfig` 的结构性字段（name/systemPromptKey/allowedTools 的 yaml 镜像） | 结构代码即真相；yaml 只留参数覆盖 |

## 设计契约（实现唯一权威）

### 目标形态

```
Pipeline（编排层：意图识别 + 路由规则表 → 选中执行者）
   ├─ RetrievalAgent（检索型：knowledge / react_loop，产物=上下文，回答权在 pipeline）
   └─ WorkflowAgent（workflow 型：声明 definition + 可选钩子）
        │ 委托
        ▼
      WorkflowEngine（公共骨架 = 横切保证，业务零建设）
        ├─ 生命周期：槽位收集 → 阶段序列（when 条件跳过）→ replan 检查点 → Outcome
        ├─ 错误处理：阶段级 workfolwErrorPolicy
        ├─ 观测性：固定切点自动记 trace（step 进入/退出/耗时/llmCalls/toolCalls/stopReason）
        ├─ 数据校验：入参槽位校验 + 阶段产出 outputGuard（复用 JsonSchemaValidator）
        ├─ 预算：workflow 级合计（maxLlmCalls / timeoutSeconds，超限 ESCALATE）
        └─ 会话恢复：AWAITING_USER（已有）
```

### 灵活性四层次

| 层次 | 机制 | 覆盖场景 |
|---|---|---|
| ① 声明式定制 | `WorkflowDefinition` 字段差异 | 90% 任务差异，零代码 |
| ② 条件路由 | `StageSpec.when(Predicate<AgentSessionState>)` | 非线性主形态："无 traceId 跳过日志精查" |
| ③ 行为钩子 | `WorkflowAgent` default 方法（4 个） | 引擎默认不合身时局部覆写，不 fork 引擎 |
| ④ 节点形态 | StageSpec 支持 agent 引用（StepAgent） | **只留缝不实现**（批次 3 文档化） |

### 钩子清单（全部 default，不覆写=引擎默认）

```java
default void beforeStages(AgentTask task, AgentSessionState session) {}
default String buildStageUserMsg(StageSpec stage, AgentTask task,
                                 String knowledge, AgentSessionState session, int attempt) { /* 引擎默认拼法 */ }
default StageRecovery onStageFailed(StageSpec stage, Throwable error, int attempt) { return null; /* null=走 workfolwErrorPolicy */ }
default AgentOutcome finalizeOutcome(AgentOutcome outcome, AgentSessionState session) { return outcome; }
```

### 决策记录（讨论已定 + v2 修订，不再重议）

- **不做 DAG/状态机**：线性 + when 跳过 + replan 微调覆盖可预见形态；图编排是纯复杂度，
  真到那天 StageSpec 升级图节点是兼容演进。
- **检索型范式不套 workflow 框架**：产物=上下文、流式生成/引用/缓存写回在 pipeline 侧，
  套框架无收益。双形态以接口显式化（`RetrievalAgent`）落地。
- **`AgentOutcome` 保持统一不拆分**：record + kind 判别已是 sealed 的轻量等价；分派点仅
  pipeline/eval 两处，统一形状的简洁 > 双 Outcome 的编译期类型安全；跨形态演进
  （如检索型未来要澄清）不受限。
- **overrideReplan 钩子砍掉**：绕过 LLM 裁决的口子使 replan 行为不可只看引擎而知；
  省调用诉求由确定性跳过（replanAfter=false，已有）承担。
- **DegradeGuard 不下沉引擎**：它是编排层并发闸（闲聊/RAG流式/ops 三路），下沉则 workflow
  反向依赖 service.pipeline，分层方向错。
- **路由规则不做 yaml/DSL**：路由是行为不是配置，@Component 代码注册保类型安全与可测性。
- **yaml 阶段覆盖降维（v2）**：结构（阶段序列/prompt key/工具白名单/when/guard）代码即真相，
  yaml 只覆盖**运行参数**（maxSteps/replanAfter/workfolwErrorPolicy）——保住运维热调参能力，
  删掉 StageConfig↔StageSpec 双形状维护。
- **StageListener SPI 暂不建**：观测切点先收敛进引擎；有第二个消费方（告警/审计）再抽。
- **模块分离两刀（2026-09-12 用户定）**：第一刀抽 `rag-agent-framework`（core/slot/workflow，
  零业务依赖；业务工具与装配留 chat 域）；第二刀（retrieval/observe 独立）按真实复用需求再切，
  不预拆。时机锁死在框架化批次之后——契约先稳定，模块边界一次到位。先仓内 Maven 模块，
  边界验证后再考虑抽独立坐标（对齐 llmobservability 先例）。
- **diagnose 双实现合流**：`LogDiagnoseService` 编排删除，REST 端点薄壳化调 `OpsDiagnoseAgent`（#2.5）。
- **意图缓存与检索缓存口径切换不做兼容**：key 内容变了自然 miss（docver/TTL 兜底），
  不写迁移代码。

---

## 批次 1：workflow 框架重写（P0）

### #1.1 新契约定义
- [x] `WorkfolwStageSpec` 重写：`name / systemPromptKey / allowedTools / maxSteps / replanAfter /
      when(Predicate<AgentSessionState>，null=总执行) / workfolwErrorPolicy(默认 AS_IS) / outputGuardSchema(null=不校验)`
      + 旧五参便捷构造 + `withParams` 参数覆盖视图
- [x] `WorkfolwErrorPolicy`：`AS_IS`（LLM_ERROR 出环照常进 replan——现语义）/ `RETRY(n)` / `SKIP` /
      `ESCALATE`（转 AWAITING_USER 追问）/ `FAIL` + yaml 解析 `parse("retry:2"/"skip"/...)`
- [x] `StageRecovery`（钩子返回类型，四态，RETRY=再试一次）
- [x] `WorkflowDefinition` 加 `maxLlmCalls` / `timeoutSeconds`（<0=全局默认；旧六参便捷构造保留）
- [x] `WorkflowAgent` 4 钩子落接口（beforeStages / buildStageUserMsg / onStageFailed / finalizeOutcome，
      全 default 不覆写=引擎默认）

### #1.2 WorkflowEngine 重写（横切内建）
- [x] when 求值：跳过记 trace step（action=stage.name，output=SKIPPED）
- [x] workfolwErrorPolicy 分派：RETRY 计数独立于 maxAdjustRetries；ESCALATE 复用 saveAwaitingUser 路径；
      onStageFailed 钩子优先于 policy；RETRY 耗尽降级 AS_IS 交 replan 兜底
- [x] 预算：阶段入口 + 重跑/重试前检查（ADJUST×RETRY 乘积级消耗堵住），超限 ESCALATE
      （trace 记 budget_exhausted）
- [x] outputGuard：schema 非空且 finalText 非空时校验（非 JSON/不符=GUARD_FAILED）；
      **AS_IS 下护栏只观测不拦截**（默认语义：挂 guard 不配 policy = 观测模式）
- [x] 自动观测：固定切点记 trace（阶段退出=终稿/出环原因+工具次数；guard/skip/budget 专属 action）；
      前端 AgentTraceTree.vue 已确认宽容渲染（未知 action 落灰 tag），无破坏
- [x] `buildStageUserMsg` 引擎拼法平移为 WorkflowAgent 默认实现（引擎改调钩子）
- [x] "末阶段必须 FINISH_TOOL/FINAL_TEXT"硬编码移除
- [x] 主入口 `run(WorkflowAgent, AgentTask)`（钩子回调）；`run(WorkflowDefinition, AgentTask)` 保留为
      零钩子兼容入口
- [x] 顺手修正 runStage 怪癖：合并后槽位进工具可见上下文（旧实现取 task.session() 原始视图，
      单轮丢本轮抽取/预填——javadoc 注明的有意行为变更）
- [x] **JsonSchemaValidator 归位 core**：原在 tools/support，引擎引用触发 fitness 违规
      （workflow 不得依赖 tools；tools 亦不可依赖 workflow）——纯函数设施放最底层

### #1.3 ops 重新装配（行为以新契约为准，不追求与旧引擎逐分支等价）
- [x] `OpsStageCatalog` 重写：结构代码即真相；yaml 降维为参数覆盖（name 匹配 maxSteps/replanAfter/
      workfolwErrorPolicy，未知 name 告警忽略）
- [x] ops 新默认：resolve 阶段 workfolwErrorPolicy=RETRY(1)（核心产出段失败自动重跑一次）；
      verify 不挂 outputGuard（现场决策：finalText 是 markdown 结论文本非结构化报文，报文校验
      已由 validate_request 工具承担——guard 能力留给"末阶段产出 JSON"的未来 workflow）
- [x] `OpsDiagnoseAgent`：`run → workflowEngine.run(this, task)` 零钩子装配
- [x] `AgentProperties.StageConfig` 降维（name/maxSteps/replanAfter/workfolwErrorPolicy）；
      `AgentProperties.Workflow` 段（max-llm-calls=24 / timeout-seconds=300）

### #1.4 配置与守护
- [x] 配置段落地（见 #1.3）；yaml 现网未配置 ops.stages（零兼容负担，确认过）

### #1.5 验收（场景驱动，替代等价验收）
- [ ] ops E2E 五场景：traceId 直达 / 首问一次问齐 / 追问恢复不重问 / 三阶段直答 / replan adjust 重跑
      （需启动后端联调——待用户环境）
- [x] 新能力单测（WorkflowEngineTest 新增 6 用例）：when 跳过 / RETRY 重跑成功 / SKIP 放弃续跑 /
      预算超限 ESCALATE / guard AS_IS 观测模式 / ErrorPolicy 解析
- [x] **全量 180 测试绿**（含 ArchitectureFitnessTest 守护——本轮实际拦下一次跨层违规）

---

## 批次 2：编排层与契约现代化（P1，pipeline/eval 一拨改完）

### #2.1 Agent SPI 双形态显式化
- [x] `RetrievalAgent extends Agent`（core 包标记接口，契约 javadoc：产物=上下文，回答权在 pipeline）；
      `KnowledgeAgent`/`ReactToolLoopAgent` implements 之
- [x] `AgentTask` 拆分：**grep 证实 intent 字段全库零消费者，直接删除**；searchContext 留公共
      （workflow 阶段工具经 AgentContext 消费检索参数）；ops 不再被迫携带 IntentResult
- [x] `AgentOutcome` 统一保留（决策记录）+ 新增 `isEmpty()`（替代旧 RetrievalVerdict.EMPTY 判空）

### #2.2 路由表化（新包 `chat/service/pipeline/route/`）
- [x] 四件落地：`RouteContext` / `RouteDecision`（agentType + prefillSlots）/ `RouteRule`
      （priority + match + domainDescriptor）/ `RouteRegistry`（排序求值 + 域清单聚合）
- [x] ops 域两条规则（impl/ops 域聚）：`OpsTraceIdRouteRule`（priority=10，traceId 预填随
      RouteDecision 走，OpsSlotCatalog.sanitized 逻辑随迁）+ `OpsDiagnoseIntentRouteRule`
      （priority=50，domain 匹配 + 置信度门槛 + agentChoice=knowledge 压制；域描述挂在意图规则上）
- [x] **规则表只裁决"选哪个执行者"**：会话恢复/悬空指代澄清/闲聊短路/agentChoice 强制保留 pipeline 主干
- [x] pipeline 路由段重写为**两次求值**：意图分类前（intent=null，短路类规则命中省 LLM 往返）
      + 分类后（意图域规则）；优先级链与现状一致（traceId > 恢复 > 显式选择 > 意图 > 默认）
- [x] 全局行为（恢复/澄清/闲聊/agentChoice instanceof WorkflowAgent 分流）不变

### #2.3 意图分类去域感知
- [x] `IntentResult` 重写：`domain` + `confidence` + `reason` + `needsWebSearch`；
      `needsRetrieval()` 派生方法（domain ∉ {greeting, chitchat}）；基础域常量内聚
- [x] `DefaultIntentClassifier` 重写：输出 `{domain, confidence, needs_web_search, reason}`；
      域清单 = classify-user.md 基础域 + `RouteRegistry.domainDescriptors()` 动态拼装段
- [x] classify-user.md 重写（基础四域：knowledge/web_search/greeting/chitchat；动态段 Java 侧追加）
- [x] `SearchContext.metadata`：key 名 "intent" 保留（**EVAL 旁路零改动**），值域
      knowledge_query→knowledge 等；检索缓存 key 含该值，口径变化自然 miss
- [x] `CacheKeys.intentKey` 拼口径版本 `v2-domain` bump
- [x] 消费点改造全集：pipeline（日志/metadata/needsRetrieval 派生调用）、`TelemetryDimensions`
      （getDomain）、`CachingIntentClassifierTest` 适配

### #2.4 legacy 删除（让历史升级）
- [x] `EvalRunner` 改吃 `AgentOutcome`；`TelemetryDimensions` 维度挂 `AgentOutcome`；
      `ChatOrchestrator` legacy 段消除
- [x] 删除 `core/legacy/` 三件套（AgentRetrievalAdapter / AgentRetrievalResult / RetrievalVerdict）
- [x] fitness 无 legacy 专项断言，零改（守护扫描有效性规则通过）

### #2.5 diagnose 双实现合流（让历史升级）
- [x] `DiagnoseController` 薄壳化：组装 AgentTask（traceId 预填槽位）→ `OpsDiagnoseAgent.run()`；
      CLARIFY 时拼追问清单返回（REST 单轮语义注明）
- [x] 删除 `LogDiagnoseService`（295 行）+ 旧编排 dto 三件（DiagnosePreview/MatchResult/RelatedDoc）
      + diagnose 双 prompt（log-diagnose.md/match-check.md）
- [x] `DiagnoseResponse` 重构为 agent 出参投影（traceId/conclusion/outcome/llmCalls）；
      前端消费核查 = ApiConsole 调试台仅展示 JSON，无强类型绑定，结构变化安全

### #2.6 验收
- [ ] 六路由场景 E2E（含 traceId / 恢复会话 / 显式 ops / 显式 knowledge / 低置信度落回 / 闲聊+RAG）
      —— 待整体联调（用户指示批次完成后统一测试）
- [ ] eval 跑批回归：knowledge 基准无异常漂移 + 对照面板多范式可用 —— 待整体联调
- [ ] diagnose REST 端点验证（薄壳化后功能等价）—— 待整体联调
- [x] **全量 180 测试绿**（含 fitness 9 规则守护）

## 批次 3：留缝（纯文档，零代码）

- [x] `WorkfolwStageSpec` javadoc 写明 StepAgent 演进方向（批次 1 重写时同步落：节点形态 = inline loop 或
  agent 引用；触发条件 = 第二个 workflow 且步骤重叠；届时 ReactToolLoopAgent 零改造即首个
  StepAgent）——`WorkflowEngine` javadoc 同步（阶段循环骨架不变的承诺）
- [x] 不建空接口/占位字段（零新增死代码；删除的 legacy/旧诊断编排净减约 340 行）

---

## 批次 3：留缝（纯文档，零代码）

- [ ] `WorkfolwStageSpec`/`WorkflowEngine` javadoc 写明 StepAgent 演进方向（节点形态 = inline loop 或 agent
  引用；触发条件 = 第二个 workflow 且步骤重叠；届时 ReactToolLoopAgent 零改造即首个 StepAgent）
- [ ] 不建空接口/占位字段（死代码零容忍惯例）

---

## 阶段 4：模块分离第一刀——rag-agent-framework 抽出（框架化批次完成后）

前置理顺（反向依赖归位，可与批次 2 同步做）：
- [ ] `DocumentVersionStamp` 从 chat.cache 挪 retrieval 域（ingestion 的 docver bump 不再伸进 chat）
- [ ] `RagContextAssembler` 归位评估：留 chat 或随 retrieval 域走（eval 依赖方向随之定）

抽出：
- [ ] 新 Maven 模块 `rag-agent-framework`：core（Agent SPI / tool 体系 / ToolLoopEngine）
      + slot + workflow（本计划重写后的新引擎）
- [ ] 业务侧留 chat 域：tools/rag、tools/ops、impl/ 装配、registry、session/trace 持久化
      ——framework 零持久化、零 rag 业务依赖
- [ ] `ArchitectureFitnessTest` 规则升级为模块边界断言（模块编译期已强制的规则可删减）
- [ ] `PromptSnapshot` 数据类与 `AgentContext.prompt(key)` 契约随框架模块走
      （prompt 计划 P2 落地的类物理迁移）
- [ ] app 聚合装配：先组件全扫（快速见效），starter/autoconfiguration 化留到抽独立坐标时再做
- [ ] 配置段归属整理（`rag.chat.agent.*` 拆框架段与业务段）

验收：`rag-agent-framework` 的 pom 依赖中无任何 rag 业务模块；全量测试绿；fat jar 产物不变。

## 阶段 5（按需，不预拆）：第二刀——retrieval / observe 独立

触发条件：出现第二个消费者（其他项目/独立服务要复用检索或观测能力）再切。

---

## 新增 workflow 的目标成本（计划完成的验收口径）

四件事，pipeline/引擎零改：
1. `RagParadigm` 加枚举（刻意注册点）
2. impl 包：definition +（可选）钩子 + RouteRule @Component（含域描述）
3. 意图分类 prompt 域清单自动带入（零分类器代码改动）
4. 前端选项

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 重写无等价红线，回归靠场景与 eval 兜底 | 验收场景集固定进本文档（#1.5/#2.5）；eval 基准漂移 >1pp 逐项归因 |
| trace step 新格式破坏前端 trace 面板 | #1.2 动手前先读 `AgentTraceTree.vue`/`chat.ts` 解析逻辑；SSE trace 事件结构变更同步前端 |
| AgentTask 拆分波及 eval/评测构造点 | `forEval` 工厂保留同签名；编译期暴露全部消费点 |
| 意图 prompt 重写后分类质量漂移 | 域清单动态拼装 + 上线后观察意图缓存命中率与路由分布（CacheStats 看板） |
| ops 新默认（RETRY/guard）引入未知行为 | 每项新默认独立开关位（StageSpec 显式声明，不用隐式全局默认），可单独回退 |

## 后续衔接（与 prompt×agent 组合设计的接口点）

prompt 组合设计讨论已启动（2026-09-12），方向：prompt 资产化（逻辑 key 与内容版本分离）+
能力包（bundle = key→版本 不可变快照，agent 绑定包获得行为）。与本计划的挂点：
- prompt 消费点全景：`WorkflowDefinition` 的 3 类 key（slotExtract / stage.systemPromptKey /
  replan）+ 检索型（agent/react-loop、rag-answer-kb）+ 意图分类 prompt（#2.3 后动态拼域）
- 钩子 `buildStageUserMsg` 是运行时 prompt 组装的唯一业务入口
- bundle 解析时机在 AgentTask 构造（编排层），引擎/工具循环消费内容快照——框架化重写不受阻，
  PromptSnapshot 数据类与 prompt(key) 契约在阶段 4 随框架模块走（接口在框架、实现在业务）
- 缓存联动：答案/意图缓存 key 拼 prompt 内容 hash（修掉"改 prompt 缓存重放旧答案"隐患）
- 完整设计（key 空间/4+1 表/两层组合/装配机制/分期）见 `plan/2026-09-12-prompt-bundle.md`
