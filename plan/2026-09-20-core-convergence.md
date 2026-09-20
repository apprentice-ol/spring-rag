# 2026-09-20 核心收敛：契约进 core、实现进平台、策略留业务

> 背景问答：三条业务线（诊断 ops / RAG / 闲聊）的 context、trace、session 是否"各做各的"、能否收敛到 agent 核心。
> 调研结论（三路并行探查）修正了前提：应用层轨迹已统一（同格式同表同入口）、历史窗口只有一份实现、
> 消息/任务两个世界拆得对。真正的病灶是三处：**core 契约残缺**（轨迹无绝对时间戳）、**core 钩子未接线**
> （StartOptions.traceId 闲置、span 体系休眠）、**出口语义靠命名约定**（escalate_node 字符串）。

## 一、判据与三层模型

唯一判据：**会不会被下一条业务线复制？** 会被复制的是机制，必须收敛；必然自定的差异是策略，声明即可。

```
业务线（ops / RAG / chitchat / 第四线…）   ← 策略：窗口参数、判据、prompt 边界、域语义
        ↑ 声明
平台机制层（shared + 能力域 + business 横切） ← 机制唯一实现：生命周期、装配、持久化、传播
        ↑ 实现
agent-core（com.agentframework，零依赖）     ← 契约与原语：引擎、槽位、HITL、轨迹契约、span SPI
```

- 契约进 core、实现进平台、策略留业务。
- "core 不做实现"只约束存储/导出（SessionStore/SlotStore/SpanExporter 均是此模式），**不禁止 core 出契约**。
  （旧论证"内核状态不可跨执行查询所以 Findings 不进 core"只排除实现，不排除契约——见 §五 C7。）

## 二、逐项裁决总表

| # | 事项 | 裁决 | 归宿 |
|---|---|---|---|
| C1 | 轨迹步绝对时间戳 | **收敛（core 契约残缺）** | ExecutionTraceStep 补字段 |
| C2 | traceId 对齐 | **收敛（钩子闲置）** | StartOptions.traceId 接线 |
| C3 | 节点终态语义 | **收敛（命名约定→声明）** | NodeDefinition.terminalKind |
| C5 | core span 体系休眠 | **激活**（SpanExporter/Tracer→OTel 桥） | 平台侧桥接实现 |
| C6 | 会话扫描契约 | 延后（reaper 尚可直接凿 PG，不阻塞） | core SessionStore 加只读扫描面 |
| C7 | ClaimLedger 契约 | 延后（触发：第四条线需要"跨轮可否定结论"） | core SPI + 平台关系实现 |
| P1 | 任务/尝试生命周期正名 | 延后（事实上已线无关，搬移是结构工程） | business/task → 平台服务，任务类型参数化 |
| P2 | 有界装配器（4+ 份截断实现） | 延后（触发：下一个注入点出现） | business 横切 util |
| P3 | findings/claims 渲染双份复制 | **收敛（本次）** | 共享渲染器，编号契约代码化 |
| P4 | 线程上下文传播三份实现 | 延后（install 已全局，存量重复无害化中） | 收敛到 TaskPropagation 一份 |
| P5 | recentUserText 裸窗口 | 延后（仅 1 消费方） | 收编进 HistorySpec 语义 |
| P6 | cacheable 声明化 | 延后（与 P8 参数对象同一待办） | 请求上下文声明式属性 |
| P8 | streamRagResponse 12 散参 | 延后（触发：新增编排分支时） | 参数对象 → 分支模板 |
| — | Workspace/Memory 死壳 | 延后（ContextManager 手术，单独批次） | 从 core 删除 |
| — | RAG 采纳 Task（attempt≡1） | 延后（可选，理论已统一） | Task 层可选注册 |

保持域自治（结论：自治即正确，收敛反而是错）：窗口/预算参数、prompt 边界修辞（模式库问题）、
Findings 的 kind 语义、targetShifted 判据、SlotProvenance、引用角标契约、缓存键构成、
"无 agent 执行→无轨迹"的短路分支选择权。

## 三、本次实施批次（2026-09-20）

### C1 轨迹步绝对时间戳

- `engine/core/ExecutionTraceStep`（record）在 `durationMs` 后加 `startedAtMs`/`endedAtMs`（epoch 毫秒）；
  `toDocument()` 非零时输出。
- `engine/workflowruntime/DefaultWorkflowRuntime` 节点循环：`nodeStartNanos` 旁记墙钟
  `nodeStartWallMs`，`traceStep(...)` 加参透传；三个调用点（suspend/failed/normal）同步；`endedAtMs = 开始 + duration`。
- 业务侧 `trace/EngineTraceMapper`：
  - 轨迹根锚点优先取第一步 `startedAtMs`（真实执行起点），无则保留 `now − engineDuration` 反推；
  - `stepDetail` 增补 `startedAtMs`（有值时）——绝对时间入 detail 通道，前端契约向后兼容（additive）；
  - 单步 latency 语义不变（`now − durationMs` 技法产出的差值恒等于真实耗时，见原注释）。
- 效果：消灭"每个数字都偏"的补偿链路第一步（反推起点），后续可完全删除反推逻辑。

### C2 traceId 接线

- core `runtime/session/StartOptions` 加 `withTraceId(String)`（对齐既有 with* 风格；null 安全——空则引擎自生成）。
- `ops/OpsRunner.execute`：
  - REST 单轮（task==null）：`StartOptions.defaults().asEphemeral().withTraceId(currentTraceId())`；
  - 新 attempt：`.withTraceId(task.chainTraceId() != null ? task.chainTraceId() : currentTraceId())`；
  - 挂起恢复（resume）不动——会话 traceId 已落会话。
- `knowledge/KnowledgeRunner.retrieve`：`.withTraceId(ctx.traceId())`（RunContext 已携带编排层 otelTraceId）。
- 效果：引擎会话（ops_engine_session 的 SessionRecord）、引擎事件（EventBus 带 traceId）、
  以及 C5 激活后的引擎 span 根，三处与业务链 traceId 对齐；ephemeral 路径虽不落库，
  但 DefaultEngine.startTrace 直接吃 session.traceId()（core 已留此传参点）。

### C3 节点终态语义

- core 新增 `definition/node/TerminalKind`（enum：FINISH / ESCALATE / ASK；`of(String)` 解析，未知返回 null）。
- `definition/node/NodeDefinition` 加 `default TerminalKind terminalKind()`：读 meta 属性 `terminalKind`
  （与既有 `isTerminal()` 的 meta 属性机制同风格，七个 NodeDefinition 实现零改动）。
- `ExecutionTraceStep` 末尾加 `terminalKind` 字段（nullable）；`DefaultWorkflowRuntime.traceStep` 从 node 取；
  `toDocument()` 非空输出。
- 图声明：ops 图与 knowledge react 图的 `escalate_node` meta 加 `withAttribute("terminalKind", "ESCALATE")`。
- `engine/outcome/RunOutcomeMapper`：声明优先（executionTrace 中存在 terminalKind==ESCALATE 的步 → ESCALATE），
  保留 visitedNodes.contains(escalate_node) 与 escalate_reason 槽位兜底——旧图定义未声明时行为不变。
- 效果：第四条线声明终态语义即可被出口映射识别，不再复制节点命名约定。

### C5 span 体系激活：OtelSpanTracer 桥

- 新文件 `business/engine/OtelSpanTracer implements com.agentframework.crosscutting.trace.Tracer`：
  - `startTrace(traceId, name, kind, attrs)`：经 `GlobalOpenTelemetry.get().getTracer("agent-core")` 建 OTel span；
    **父级优先取 ambient `Span.current()`**（TaskPropagation 已把请求链上下文搬进引擎线程 → 引擎 span
    成为请求 span 的真子 span，traceId 天然对齐）；无 ambient 且 traceId 为 32 位 hex 时以非记录
    SpanContext 强制该 traceId 作根；同时建 core Span 镜像（core 内部按 parentId 串联）。
  - `startSpan`：按 core 父 span id 查映射取 OTel 父 context；双镜像入 `Map<coreSpanId, otelSpan>`。
  - `endSpan`：用 core span 的 startTime/endTime 回填 OTel（setStartTimestamp + end(Instant)），映射移除。
  - `recordEvent/setAttribute` 透传；`flush/finishedSpans` no-op（OTel 自行导出）。
  - SpanKind 映射：core AGENT→INTERNAL，其余同名直传。
- 装配：`AgentEngineConfiguration` 的 `EngineBuilder.create()....tracer(new OtelSpanTracer())`。
  默认启用——运行时未配 OTel 导出时 GlobalOpenTelemetry 为 no-op，天然安全降级。
- 效果：引擎内每次 run（agent:<id> 根）与每个节点（node:<id>）自动进 OTel 链；
  **ops 线引擎内 LLM 调用的可观测不再依赖手工 @TelemetryStep 埋点**（此前 RAG 线 17 处、ops 线 0 处的采用不均被抹平）。

### P3 findings/claims 渲染器收敛

- 新文件 `business/task/FindingsText`：`renderNumbered(List<AgentFinding>, maxItems, claimMax)`
  产出 `[#n] [kind] claim` 行序列 + 显式省略行；常量（10 条 / 400 字符）收敛为一份。
- `OpsRunner.renderFindings` / `TaskFollowUpQa.renderClaims` 改调共享渲染器（前者拼标题段，后者空列表回 "（无）"）。
  输出逐字节等价——编号顺序与 activeOf 查询一致的锁步从"注释约定"变"同一函数"。

### 小清理

- `KnowledgeAnswerService.chitchat`：删除无人消费的 `ChatMemory.CONVERSATION_ID` advisor 参数
  （ragChatClient 无 ChatMemory advisor，参数是死的）及其 import；`conversationId` 形参与调用方同步收掉。
- `OpsRunner.currentTraceId` javadoc：`AgentSessionServiceImpl` → 现职责归属 `AgentTaskServiceImpl`（chainTraceId 首轮写入）。
- `AgentEngineConfiguration` 类注释：澄清会话表述改为 sa_agent_task 口径（sa_agent_session 已废弃）。

## 四、验证

## 四、验证

> **实施状态（2026-09-20 当日）**：C1/C2/C3/C5/P3/小清理全部落地；
> 测试 agent-core 164 绿、business 135 绿、knowledge 36 绿、delivery 10 绿，全仓 test-compile 干净。

### 真机验证（2026-09-20，docker 浏览器 + 应用 API + DB）

| 项 | 结论 | 证据 |
|---|---|---|
| UI 回归 | ✅ 无回归 | 知识问答（引用角标/轨迹抽屉/traceId/链路按钮）、诊断挂起卡片（补充信息选项 +「我理解的信息」）均正常渲染 |
| C1 时间戳 | ✅ | `sa_agent_trace.steps[].detail.startedAtMs` 在 knowledge 与 ops 两条线的每一步都有值 |
| C2 traceId | ✅ | 重启后新任务 `ops-7a9a84b1…#1`：引擎会话 `record.traceId` == `sa_agent_task.chain_trace_id`（`aligned=t`）；改动前历史任务全部 `f` |
| C3 终态声明 | ✅ 声明路径生效，**发现并修复一处漏声明** | 真机发现 `KnowledgeQaGraphFactory` 的 `kb_done`/`kb_shortcircuit` 未加 `terminalKind`（我初版只改了 react 图与 ops 图）→ 已补 `FINISH`；挂起运行不含终态节点，故 ops 侧 terminalKind 未在真机出现（符合预期） |
| C5 引擎 span | ⚠️ **本地无法验证（OO 故障，与改动无关）** | 见下 |

**C5 验证受阻的环境问题（独立故障，需另行处理）**：
- OpenObserve 的 traces 流**近 24 小时 0 条可检索**（`GROUP BY service_name` 空集），但流统计声称 `doc_time_max` 是当天；
- 绕过 collector **直发 OO `/api/default/v1/traces` 返回 HTTP 200**（`partialSuccess: null`），随后按 `service_name`/`trace_id` 检索仍 0 命中；
- 同实例**日志流正常**（`springai_rag_logs` 当天数据可检索，最新到当前分钟）——说明 OO 只有 trace 摄入→可检索这条路径坏了；
- 应用侧无桥接异常（日志搜 `otel span end failed` / `OtelSpanTracer` 均 0 命中），故 OtelSpanTracer 未抛错；
- OO 容器 stdout 日志停在 09-03，且 `docker logs --since 45m` 为空（与其仍在服务 API 矛盾）。
- **结论**：OO 实例的 trace 索引已卡死（历史上有过「08-24 起 traces 断流」同类记录）。C5 需在 OO 恢复后复验：OO 里应出现 `agent:<agentId>` 根 span + `node:<id>` 子 span，且 traceId 与 `sa_agent_trace.trace_id` 一致。

- `mvn -pl platform-agent-core,platform-business,platform-knowledge -am test`（core record 变更波及面全在编译期暴露）。
- 重点测试：core 引擎/workflowruntime 既有测试（ExecutionTraceStep 构造）、
  business 的 AskMissingHandoffTest / OpsGraphSuspendTest / ChatMessageWriterTest（不应受影响）。
- 真机验证点（下次启动时观察）：OO 里 ops 诊断一次 run 出现 agent:ops_diagnose 根 span + node:* 子 span，
  且 traceId 与 sa_agent_trace.trace_id 一致；sa_agent_trace.steps detail 出现 startedAtMs。

## 五、延后项与触发条件

| 项 | 触发条件 | 第一步 |
|---|---|---|
| C6 会话扫描契约 | reaper 需要跨存储回收或第四条线自带引擎会话 | SessionStore 加 `listIdsIdleSince(Instant)` |
| C7 ClaimLedger 契约 | 第四条线需要跨轮可否定结论（报告线/深化 RAG 追问） | core 出 SPI + 数据模型，sa_agent_finding 实现平移 |
| P1 任务生命周期正名 | 第四条线需要挂起/恢复/多 attempt | business/task → 平台服务 + 任务类型注册 |
| P2 有界装配器 | 新增第 5 个截断注入点 | business 横切 util + 四处迁移 |
| P4 传播收敛 | 触碰 LlmCallGuard/Controller 线程模型时 | common 出 SPI，observe 注册 ContextPropagator |
| P5 窗口语言统一 | 第三个读历史的消费方出现 | recentUserText 收编 HistorySpec |
| P6/P8 缓存声明 + 分支模板 | 新增编排分支 | 参数对象 → cacheable 声明式属性 |
| Workspace 删除 | 单独批次（ContextManager 手术） | 先摘 Memory 接口消费 |

## 六、变更文件清单（本次）

core（platform-agent-core）：
- `engine/core/ExecutionTraceStep.java`（+startedAtMs/endedAtMs/terminalKind、toDocument、withIndex 透传）
- `engine/workflowruntime/DefaultWorkflowRuntime.java`（墙钟采集、traceStep 加参）
- `runtime/session/StartOptions.java`（+withTraceId）
- `definition/node/TerminalKind.java`（新增）
- `definition/node/NodeDefinition.java`（+terminalKind default 方法）

business（platform-business）：
- `trace/EngineTraceMapper.java`（真实锚点优先、detail 增补）
- `engine/outcome/RunOutcomeMapper.java`（声明优先 + 兜底）
- `engine/OtelSpanTracer.java`（新增）
- `engine/AgentEngineConfiguration.java`（tracer 装配 + 注释修正）
- `ops/OpsRunner.java`（StartOptions.traceId ×2、renderFindings 改调、注释修正）
- `ops/TaskFollowUpQa.java`（renderClaims 改调）
- `ops/workflow/stages/TerminalStageModule.java`（escalate/conclude 节点终态声明——节点构造实际在此，
  OpsDiagnoseWorkflowFactory 只持有常量）
- `knowledge/workflow/KnowledgeReactGraphFactory.java`（escalate 节点声明）
- `knowledge/KnowledgeRunner.java`（StartOptions.traceId）
- `task/FindingsText.java`（新增）

knowledge（platform-knowledge）：
- `answer/KnowledgeAnswerService.java`（删死参数）
- 调用方 `ChitchatResponder`（chitchat 签名同步）
