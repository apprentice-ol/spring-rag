# Agent × Workflow 目标态设计（2026-09-12 讨论收敛稿 v1.0）

> **2026-09-13 落地变更**：原 `platform-agent-framework-core` / `platform-agent-framework-engine`
> 两个模块已**合并为单模块 `platform-agent-framework`**，包按概念分组（`agent` / `workflow` / `node` /
> `tool` / `model` / `prompt` / `result` / `plan` / `route` / `trace` / `guard` / `session` / `cache` /
> `config` / `exception`，不再有 core / engine 层级）；`Workfolw*` 拼写修正为 `Workflow*`
> （`WorkflowStageSpec` / `WorkflowErrorPolicy`）；`WorkflowEngine` 的 `AgentInvoker` 角色抽为
> `WorkflowEngineAgentInvoker` 适配器。下文 §16 的模块结构为历史设计记录。

## 0. 文档定位

本稿是**目标态设计**，不是迁移计划：形态与组合层按讨论结论重写，不看现存实现包袱、不计改造成本。
此前两份计划的**内核主张继续有效**（引擎横切、声明式差异、钩子个性化、配置只调参、prompt 资产化）：
- `plan/2026-09-12-workflow-framework.md`（框架化批次 1-2）
- `plan/2026-09-12-prompt-bundle.md`（prompt 资产化 P1 / 组合 P2）

被本稿**替换**的只有三处（详见 §12.2/§12.3）：双形态（检索型/工作流型）、Agent 持有流程的方式（实现 → 组合）、
prompt 两层覆盖 → 三层增强。

## 1. 第一原则

1. **单执行主线**：Engine 调用主 Agent → 主 Agent 持有 Workflow → Workflow 声明节点 →
   节点调 ExtensionTool 或子 Agent（子 Agent 重入引擎）。不存在第二条执行路径。
2. **组合而非实现**：Agent 与 Workflow 是两棵独立继承树，靠"绑定"组合（Bridge），
   两者的扩展互不影响。
3. **Workflow 即范式**：范式 = `(Agent, Workflow)` 绑定。换 Agent、换绑定，都算换范式；
   注册、路由、展示、eval 一律以绑定为单位。
4. **调度与能力分离**：Workflow 管流程、Tool 管能力、Agent 管身份与交付、Engine 管骨架与横切。
5. **内核不开放，周边可扩展**：横切语义与执行骨架由框架独占；周边（节点形态、拦截器、路由策略、
   元数据、后端）对开发者开放。
6. **一切执行有指纹，一切扩展有契约**：`agent + workflow + promptHash` 进 trace / 缓存 / eval；
   扩展点是编译期接口，不是反射配置。

## 2. 五方构件契约

| 构件 | 职责 | 骨架（框架保证） | 扩展点（开发者实现） | 明确禁止 |
|---|---|---|---|---|
| **Engine** | 执行骨架 + 横切 + 路由 + 子 Agent 调度 | 生命周期顺序、节点迭代、预算、错误策略、观测切点、校验、会话、路由求值 | 节点执行器、拦截器、路由策略、元数据贡献者、后端 SPI | 不写业务规则；不认识具体工具/业务 |
| **Agent** | 身份 + 能力声明 + 交付出口 | 身份契约、能力位校验、绑定解析 | 身份（id/intentDomain/label）、能力上界、绑定哪个 Workflow、交付钩子 | 不定义流程、不实现能力、不直接调工具 |
| **Workflow** | 业务流程（范式的载体） | 阶段迭代、when、replan、策略消费 | 阶段序列、槽位目录、流程 prompt、节点形态选择、策略参数、流程钩子 | 不处理交付通道（SSE/缓存）、不自实现横切 |
| **Tool** | 原子能力（被调用） | 注册、dispatch 收口、台账与 trace | `ExtensionTool`（schema + execute + annotations） | `BaseTool` 之外不得改循环状态 |
| **Config** | 组合与开关 | 装配期校验 | 绑定（agent→workflow）、能力开关、参数覆盖 | 不改流程结构（结构代码即真相） |

### 2.1 Engine 的开放度（关键边界）

| 扩展点 | 用途 | 开放度 |
|---|---|---|
| `NodeExecutor`（新增节点形态） | 并行、人工确认等新节点 | **对外注册开放**（决策记录 D4） |
| `ExecutionInterceptor` | 鉴权、限流、审计、成本统计、提示词注入 | 开放：**可否决请求，不可改流程控制**（D5） |
| `RouteStrategy` | 自定义匹配（域/正则/分组/AB） | 开放：引擎仍负责求值顺序与优先级 |
| `MetadataContributor` | 结果元数据扩展 | 开放 |
| 后端 SPI | trace sink / session store / cache / 外部查询源 | 开放 |
| 横切语义（错误策略/预算/护栏/会话）、执行骨架顺序 | — | **不开放** |

开放 `NodeExecutor` 的前提：节点形态由引擎托管生命周期（预算、观测、错误策略、护栏），
执行器只填"节点内部怎么跑"，不得自行记账或自行吞错。

## 3. 调用链与时序

```
请求
 └─ Engine.execute(request)
      ├─ 路由：选出「主 Agent + 绑定的 Workflow」（引擎内建能力）
      ├─ 初始化：会话恢复 / 预算 / trace / 能力开关解析 / prompt 快照装配
      ├─ 驱动主 Agent（Agent 无 run：引擎执行它持有的 Workflow）
      │    └─ Workflow 阶段迭代
      │         ├─ LOOP 节点          → ExtensionTool（经 ToolRegistry 收口）
      │         ├─ DETERMINISTIC 节点 → 指定 ExtensionTool
      │         ├─ AGENT_CALL 节点    → 子 Agent（重入引擎内核）
      │         └─ 自定义节点         → NodeExecutor（引擎托管横切）
      ├─ 交付装配：ExecutionResult（内容 + 上下文 + 引用 + 指纹 + 元数据）
      └─ 交管线交付：流式 / 引用 / 缓存 / eval / SSE / 落库
```

固定时序约束：**引用映射与指纹必须在流式生成开始前就绪**（否则前端无法溯源、缓存 key 无法成立）。

## 4. 嵌套与重入（子 Agent）

节点形态 `AGENT_CALL`：

```
AGENT_CALL {
  target        : 子 Agent id（声明式写死）
  inputMapping  : 父上下文 → 子输入（槽位 / 文本 / 证据片段，声明式 key 映射）
  outputMapping : 子结果 → 父上下文（结论 / 证据 / 指标）
  budgetShare   : 预算切分上限（不是预扣）
  depthGuard    : 深度上限 + 环检测
  onFailure     : 子失败在父侧走哪个 workfolwErrorPolicy
}
```

重入语义（决策 D6）：
- **同一执行内核**：子 Agent 由 Engine 嵌套执行，不另起执行路径；横切只有一份实现。
- **预算**：子消耗计入父总账；父可给上限，不可被绕过。
- **trace**：子执行是父节点下的嵌套 span，父子 identity（agent+workflow+promptHash）都记。
- **能力**：子 Agent 有自己的能力开关（继承全局配置，可再收窄）；子产出的证据/引用**上卷**到父
  （ref 编号重映射 + 去重 + 来源保留），父的能力位才能兑现。
- **上下文隔离**：子有自己的 prompt 快照与槽位视图，父只通过 `inputMapping` 传参，不共享会话。
- **错误**：子失败不自动冒泡成父失败，交父节点 `workfolwErrorPolicy` 处置。
- **防护**：深度上限、环检测（A→B→A 拒绝）、单节点调用次数上限。

## 5. 能力三方模型（能力位）

生效能力 = **声明 ∩ 供给 ∩ 开关**：

| 角色 | 承担 | 例 |
|---|---|---|
| Agent（声明上界） | 我具备哪些可交付能力 | knowledge 声明：STREAMING / CITATIONS / ANSWER_CACHE / SEMANTIC_CACHE / RETRIEVAL_METRICS |
| Workflow（供给） | 我这条流程能产出哪些元数据 | `knowledge_qa_fast` 供给：ContextBundle + CitationIndex + RetrievalStats |
| Config（开关） | 这一次是否真的执行该能力 | eval 跑批关缓存，线上开缓存 |

规则：
1. **配置只能收窄**（不能开启 Agent 未声明的能力）。
2. **Workflow 只能供给**（不能凭空新增能力，但可声明"产不出"）。
3. **交集不足要报错**（声明且启用但供给缺失 ⇒ 装配期失败，不静默降级）。

| 能力位 | 依赖元数据 | 管线行为 |
|---|---|---|
| `STREAMING` | `GenerationSpec` + `ContextBundle` | 流式生成（打字机） |
| `CITATIONS` | `CitationIndex` | 流式前下发引用事件 + `[N]` 溯源渲染 |
| `ANSWER_CACHE` | `ExecutionFingerprint` + docver | 组装缓存 key、读写、命中重放 |
| `SEMANTIC_CACHE` | `ExecutionFingerprint` | 语义层查/写、命中回写 |
| `RETRIEVAL_METRICS` | `RetrievalStats` + `ContextBundle` | eval 记录 Recall@k 与对照 |

扩展：开发者可经 `MetadataContributor` 追加自定义元数据（自定义能力位随元数据契约版本化）。

## 6. 引擎出口与元数据接口

```java
// 引擎统一出口：内容 + 元数据 + 指纹
public record ExecutionResult(
        OutcomeKind kind,                // WITH_CONTEXT / DIRECT / CLARIFY / ESCALATE
        String text,                     // 流程末节点终稿（未声明 STREAMING 时即最终答复）
        ContextBundle context,           // 证据/检索片段（带 ref 编号、来源、分数、通道）
        CitationIndex citations,         // [N] → 来源映射（前端溯源、落库引用）
        GenerationSpec generation,       // 需要管线生成时：answer prompt key + 内容快照 + 上下文装配结果
        ExecutionFingerprint fingerprint,// agent + workflow + promptHash + release 指纹
        RetrievalStats retrievalStats,   // 检索明细（eval 用）
        AgentTrace trace) {}

// 执行中流出（引擎 → 管线）：解决时序问题（引用/指纹必须先于流式）
public interface ExecutionMetadataSink {
    void onContextReady(ContextBundle context);
    void onCitationsReady(CitationIndex index);
    void onFingerprint(ExecutionFingerprint fingerprint);
    void onRetrievalStats(RetrievalStats stats);
    void onTraceUpdate(AgentTrace trace);
}
```

契约要求：**类型化**（不用裸 Map）、**双通道**（结果内携带 + 执行中回调）、**版本化**
（SSE 事件、缓存载荷、eval 记录都消费它，属于对外契约）。

`GenerationSpec` 采用"全给"语义（决策 D7）：answer prompt 的 key + **内容快照** + 上下文装配结果 + 指纹。
理由：只给 key 会让管线回查"当前最新版"，与 trace、缓存 key、eval 复现不是同一份内容，
"改 prompt 缓存自动失效"的闭环会断。

## 7. 工具二分（BaseTool / ExtensionTool）

| 维度 | BaseTool | ExtensionTool |
|---|---|---|
| 职责 | 保障循环正常运转（控制面） | 提供能力（数据面） |
| 成员 | `finish`、`ask_user`、`escalate` | RAG 检索、日志查询、打分、重排、确定性校验、外部（MCP）工具 |
| 归属 | 框架层 | 业务/外部来源 |
| 注册 | 引擎启动期硬注册 | 注册表动态收集 |
| 白名单 | 不由白名单管理，引擎按**阶段控制开关**注入 | 只能经 `StageSpec.extensionTools` 放开 |
| 命名 | 保留字 | 撞保留字 = 启动失败 |
| 影响面 | 只改循环状态，不写业务数据 | 只产出业务数据，不改循环状态 |
| 观测 | trace 标 `kind=base` | trace 标 `kind=extension` |

## 8. Prompt 三层增强（不是覆盖）

### 8.1 key 空间（按构件分域）

```
chat/*                     链路级：意图分类、查询改写、闲聊
agent/{agentId}/*          人格级：角色、语气、交付风格、回答边界
workflow/{workflowId}/*    流程级：阶段 system、replan、slot-extract
tool/{toolName}/desc       能力级：工具说明（可被任务上下文追加）
```

### 8.2 装配语义（相加，不是顶替）

```
system = 链路级常驻段 + Agent 人格段（基础） + Workflow 任务段（增强） + 节点自身段（当前步骤指令）
user   = 资料/检索结果/用户问题（易变内容不進 system，保 prompt cache 前缀稳定）
```

- 同一 key 出现在两层 = **装配期报错**（命名空间本应互不重叠）。
- 顺序固定（基础在前、增强在后），Agent 段是**红线**（如"仅基于资料回答"），
  Workflow 段是任务指令，不得推翻红线。
- 三层内容全部进 `contentHash`；快照 `identity` 记各层 release 指纹。
- 能力包（bundle）解析一次覆盖三层；改动任一层 ⇒ 缓存自动失效。

## 9. 路由（引擎内建）

- **注册**：`(Agent, WorkflowBinding)` 注册进引擎路由表，Agent 声明 `intentDomain`；
  代码注册（`@Component` 自动收集），配置只调优先级/阈值。
- **求值**：规则按优先级求值，返回 `(Agent, WorkflowBinding, prefill)`——可同时决定 agent 与 workflow，
  即"意图识别后换 agent"或"同一 agent 换绑流程"都成立。
- **时机**：分两轮（预处理短路类 / 意图域类）；**会话恢复优先于一切路由**。
- **收敛**：闲聊/直答也可做成最小 Workflow（单节点 + 直出），使"单执行主线"彻底闭合，
  管线不再有范式特判分支。
- **观测**：路由本身是一个 trace step（命中规则、优先级、域、置信度）。

## 10. 节点形态与开发者扩展

| 形态 | 语义 | 用途 |
|---|---|---|
| `LOOP` | 模型在 extension 白名单内自主决策，多步工具循环 | 探查类（诊断、研究） |
| `DETERMINISTIC` | 直接调用指定 extension 工具，零 LLM 决策 | 单次检索、固定校验、固定拉取 |
| `AGENT_CALL` | 调用子 Agent（重入引擎） | 业务专家复用 |
| 自定义（`NodeExecutor`） | 开发者注册的新形态（并行、人工确认…） | 由引擎托管横切 |

骨架仍是**顺序 + when 跳过 + replan 检查点**，不引入图编排。

开发者视角的四种典型开发：

| 想做的事 | 实现什么 | 生效范围 |
|---|---|---|
| 加一个业务能力 | `ExtensionTool` | 任何流程按白名单引用 |
| 加一条业务链 | `Workflow` | 绑定给 Agent 后被路由选中 |
| 加一个可复用专家 | `Agent`（身份 + 能力 + 绑定 Workflow） | 独立路由，或被 `AGENT_CALL` 复用 |
| 接入自己的基础设施 / 新节点形态 | 引擎 SPI（NodeExecutor / Interceptor / RouteStrategy / MetadataContributor / 后端） | 全引擎生效 |

## 11. 不变量清单（可测试的架构守护）

1. Agent 不声明执行方法；执行只经 Engine；Agent 只持有 Workflow。
2. Workflow 只声明（含钩子），不产生副作用，不直接调 Tool。
3. 扩展白名单只出现 ExtensionTool；BaseTool 由引擎注入且不可关闭。
4. 每个 prompt key 归属四层之一；跨层同 key = 装配期失败。
5. 每次执行产出 `agent + workflow + promptHash` 三元指纹，进 trace / 缓存 / eval。
6. 能力位三方可组合：配置只收窄、Workflow 只供给、交集不足即报错。
7. 横切语义与执行骨架不可被扩展点改写（扩展只能追加观测/否决，不能改流程控制）。
8. 子 Agent 调用受深度上限、环检测、预算总账约束。
9. 引用映射与指纹先于流式生成就绪。
10. 新增能力（工具/流程/专家/节点形态）均不改引擎主干与管线。

## 12. 与旧设计的相同 / 不同

### 12.1 相同（内核主张延续）

引擎横切一次实现、声明式差异、钩子个性化、配置只调参、代码注册路由、prompt 资产化与执行快照、
单主线且不静默 fallback、工具是被调用的能力。

### 12.2 不同（结构层被替换）

| 维度 | 旧设计 | 本稿 |
|---|---|---|
| 执行形态 | 双形态（RetrievalAgent / WorkflowAgent） | 单形态（Agent 组合 Workflow） |
| Agent↔Workflow | 实现（agent 提供 definition） | 组合（Bridge，两树独立扩展） |
| 范式定义 | `RagParadigm` 枚举 + 一个 agent 实现 | `(Agent, Workflow)` 绑定 |
| 流程复用 | 无 | 子 Agent 组合（`AGENT_CALL`） |
| 交付归属 | 由形态隐式决定 | 能力三方模型 + 引擎统一出口 |
| 节点形态 | 仅 LLM 工具循环 | LOOP / DETERMINISTIC / AGENT_CALL / 可注册 |
| 工具分类 | 单一 AgentTool | BaseTool / ExtensionTool 硬分离 |
| 路由位置 | 管线层规则表 | 引擎内建 |
| Prompt 装配 | 两层 merge（同 key 覆盖） | 三层增强（相加、分域） |
| 观测 | 引擎 trace step | 三元指纹 + 子 Agent 嵌套 span |
| 预算 | 流程级上限 | 流程级 + 父子合并/切分 |
| 并发/降级 | 明确不下沉引擎 | 归引擎横切 |

### 12.3 被推翻的三条旧决策

1. "检索型范式不套 workflow 框架" → RAG 收敛为 agent + workflow（检索降为 ExtensionTool）。
2. "固定两层 merge、不做任意深度组合链" → 三层增强 + 子 Agent 递归组合；
   以**深度上限 + 环检测 + trace 全嵌套**对冲透明性损耗。
3. "DegradeGuard 不下沉引擎" → 并发/降级归引擎横切（引擎已是唯一执行入口）。

## 13. 决策记录（2026-09-12 讨论，D1-D9）

| 编号 | 决策 |
|---|---|
| D1 | 槽位目录归 Workflow（Agent 不参与输入契约） |
| D2 | Prompt 三层**增强**关系（非覆盖）；key 按构件分域 |
| D3 | 路由归引擎；`(Agent, WorkflowBinding)` 注册进引擎路由表 |
| D4 | 节点形态**对外注册开放**（`NodeExecutor`），由引擎托管横切 |
| D5 | 拦截器**可否决请求，不可改流程控制** |
| D6 | 子 Agent 调用**重入引擎内核**（同内核、预算总账、trace 嵌套） |
| D7 | `GenerationSpec` **全给**（key + 内容快照 + 上下文装配 + 指纹） |
| D8 | 能力位放 Agent 层，三方模型（声明/供给/开关）取交集 |
| D9 | 主 Agent 持有 Workflow，Workflow 可调子 Agent（组合统一为 agent 组合，取代 SUBFLOW） |
| D10 | Agent 框架**整体抽离为独立项目**实现（零业务依赖），本项目退化为业务侧消费者（见 §16） |
| D11 | 框架**不提供 starter**（不自动配置）：业务侧直接依赖 core + engine，在自有 `@Configuration` 里显式装配引擎与 SPI 实现 |

## 14. 落地顺序（按依赖，不按成本）

| 批次 | 内容 | 完成判据 | 状态（2026-09-12） |
|---|---|---|---|
| B0 | 新建框架项目脚手架（见 §16）：独立工程 + 零业务依赖 + SPI 包骨架 | 独立构建通过；依赖树中无任何 rag 业务模块 | ✅ 完成（3 模块，独立仓库路径见 §16.5） |
| B1 | 引擎出口与元数据契约（ExecutionResult / Sink）+ 能力三方模型 | 契约单测 + 现有流程行为不变 | ✅ 完成（11 单测：能力三方求解 + 结果契约校验） |
| B2 | Agent/Workflow 拆分与绑定（Bridge）+ 路由归引擎 | 同一 workflow 可被两个 agent 绑定 | ✅ 完成（绑定解析 / 路由表 / 执行计划，7 单测） |
| B3 | 工具二分 + 节点形态（DETERMINISTIC / LOOP / AGENT_CALL）+ 嵌套契约 | 子 Agent 复用跑通，预算/trace 可验证 | ✅ 完成（工具注册收口 / 三类节点执行器 / 默认驱动，19 单测） |
| B4 | Prompt 三层增强 + 能力包三层解析 + 完整 schema 护栏 + 阶段内预算 | 改任一层 prompt ⇒ 缓存失效 | ✅ 完成（三层相加 + 跨层同 key 报错 + 轻量 JSON Schema 护栏 + `BudgetView`，框架 40 单测） |
| B5 | 收敛双形态（RAG = agent + workflow）+ eval/前端切轴 | 检索指标与流式行为对拍一致 | 🚧 进行中：**线上主线已切换**——对话管线缺省检索路径与 eval 的 knowledge 轴都改走框架引擎（RAG = agent + workflow，检索为 extension tool）；ops/react_loop 移植与旧 agent 模块删除待续。业务 181 测试绿 |

### 14.2 B5 首刀（业务侧直配，无 starter）

| 交付 | 位置（业务仓 app） |
|---|---|
| 框架依赖（core + engine） | `app/pom.xml` |
| 检索扩展工具（RAG 能力下沉为 tool） | `chat/agent/fw/RetrievalExtensionTool` |
| 知识问答流程（确定性检索 + STREAMING 供给） | `chat/agent/fw/KnowledgeQaWorkflow` |
| 知识问答 Agent（身份 + 能力上界 + 组合流程） | `chat/agent/fw/KnowledgeFrameworkAgent` |
| Prompt 三层增强装配（PromptStore → 快照） | `chat/agent/fw/PromptStoreSnapshotSource` |
| 知识域兜底路由策略 | `chat/agent/fw/KnowledgeRouteStrategy` |
| 引擎直配（无 starter） | `chat/agent/fw/FrameworkAgentConfiguration` |
| 直配路径验证（产出上下文/引用/生成规格） | `FrameworkKnowledgePathTest` |

过渡项（后续批次）：`ModelPort` 目前是单跳文本实现（原生 function calling 适配待接）；旧 agent 模块
（`chat/agent/core|workflow|slot|tools` 与 `ToolLoopEngine`）与框架主线并存，待对拍后删除；
prompt key 仍是历史命名（`chat/pipeline/*`），命名空间迁移后在流程层开前缀校验。

**线上切换点（可验证）**：

| 链路 | 变化 |
|---|---|
| 对话问答（缺省路径） | `ChatOrchestrator` 不再调业务 `Agent`/`AgentExecutor`，改调 `FrameworkKnowledgeRunner` → 框架 `WorkflowEngine`（确定性检索节点 + STREAMING 供给）；chunks/trace 映射回既有结构，流式、引用、缓存、落库不变 |
| eval knowledge 轴 | `EvalRunner` 的 knowledge 范式改走框架主线（Recall@k 与上下文口径不变），react_loop 等仍走旧链对照 |
| 显式 `?agent=react_loop` | 保留旧检索链，供对拍期对照 |

**删除旧 agent 模块的前置**（按依赖顺序）：

1. 原生 function calling 的 `ModelPort` 适配（框架工具 schema → 模型工具调用 → 结构化回传），LOOP 节点才具备真实工具循环能力；
2. ops 诊断移植：槽位/会话持久化（框架 SPI 实现）+ `query_logs`/`validate_request`/`ask_user`/`finish` 工具改造（BaseTool 与 ExtensionTool 归位）+ `OpsDiagnoseWorkflow`；
3. `DiagnoseController` 与 ops 路由切到框架；
4. eval 的 react_loop 轴切到框架（或明确下线该对照轴）；
5. 前端 agent registry / trace 事件字段核对后，删除 `chat/agent/core|workflow|slot|tools|impl|registry` 旧实现与对应旧测试。

### 14.1 框架侧已完成的关键契约（B1-B3）

| 契约 | 位置（框架项目） |
|---|---|
| `ExecutionResult` / `ExecutionMetadataSink` / 三类结果工厂 | `core/result/` |
| 能力位与三方求解（声明 ∩ 供给 ∩ 开关，越界/缺供给即报错） | `core/capability/` |
| `Agent` / `Workflow` / `WorkfolwStageSpec` / `NodeKind`（值类型，可注册自定义形态） | `core/` |
| `BaseTool` / `ExtensionTool` / `ToolRegistry`（保留字与撞名保护） | `core/tool/`、`engine/tool/` |
| 路由策略 + 路由表 + 绑定解析 + 执行计划装配 | `engine/route/`、`engine/binding/`、`engine/ExecutionPlanner` |
| 节点执行器：`DETERMINISTIC` / `LOOP`（`ModelPort` 端口）/ `AGENT_CALL`（重入 + 深度上限） | `engine/node/` |
| 默认驱动：槽位澄清 / when / 预算 / 错误策略 / 护栏 / trace / 元数据流出 | `engine/driver/DefaultWorkflowDriver` |

## 15. 开放问题（未定，留档）

1. 同一节点**并行调用多个子 Agent**（fan-out）是否进设计。
2. `agent-as-tool`（把子 Agent 当工具暴露给模型动态选择）是否作为节点形态。
3. 子 Agent 调用深度上限的具体值与配置位置。
4. 自定义 `NodeExecutor` 的契约版本化与灰度策略。

## 16. 模块与项目拆分（框架独立成项目）

### 16.1 为什么独立

- 框架的复用对象是"其他项目/其他团队"，留在业务仓内会让"零业务依赖"只能靠自测约束，不能靠工程约束。
- 独立项目后，框架的依赖树就是边界的证明：**pom 里出现任何 rag 业务模块即违规**。
- 业务仓退化为消费者：升级框架 = 换依赖版本，不再耦合框架内部演进。

### 16.2 边界（谁去谁留）

| 归属 | 内容 |
|---|---|
| **框架项目**（零业务依赖） | Agent SPI、Workflow SPI、StageSpec/节点契约、ExecutionResult 与元数据契约、能力位模型、Tool SPI（BaseTool/ExtensionTool 契约）、ToolRegistry、ToolLoopEngine、function calling 协议适配、JsonSchemaValidator、PromptSnapshot 契约、AgentTrace 契约、Engine（生命周期/预算/错误策略/护栏/会话 SPI）、拦截器 SPI、NodeExecutor 注册表、RouteStrategy SPI、后端 SPI（TraceSink / SessionStore / CacheBackend）、slot 机制（SlotSpec/Evaluator/Merger） |
| **业务项目**（本仓） | 业务 Agent（knowledge / ops…）、业务 Workflow、ExtensionTool（检索/日志/校验…）、BaseTool 之外的业务工具、prompt 资产化与能力包（P1/P2，含 DB）、session/trace 持久化实现（实现框架 SPI）、pipeline 交付层（SSE/引用/缓存/eval）、配置装配与路由注册 |

**硬约束**：
1. 框架源码不得 import `com.jjx.customer.platform.*`（任何包）。
2. 框架不装配任何 Spring Bean 的具体业务实现——只提供契约与引擎；`@Component` 扫描由业务侧 starter 负责。
3. 框架不碰持久化实现，只留 SPI（session / trace / cache 均如此）。
4. BaseTool 属于框架；ExtensionTool 属于业务与外部来源。

### 16.3 新项目脚手架提案

```
agent-framework/                     ← 独立工程（独立 git 仓库，可单独发布）
├── pom.xml                          ← 根 pom（Java 21 / Spring Boot 3.5.x 依赖管理）
├── platform-agent-framework-core/            ← 契约层：SPI / 数据契约 / 能力位（最小依赖：Jackson + SLF4J）
├── platform-agent-framework-engine/          ← 引擎层：WorkflowEngine / ToolLoopEngine / 路由 / 拦截器 / 节点执行器
├── agent-framework-spring-boot-starter/  ← 装配层：自动配置 + Bean 注册（业务侧只引这一个）
└── (可选) agent-framework-test/     ← 测试基座：内存实现（TraceSink/SessionStore/CacheBackend）
```

- 依赖：Java 21、Spring Boot 3.5.x（engine/starter 依赖 spring-context，core 尽量不依赖 Spring）、
  Spring AI（仅接口，用于模型调用契约）、Jackson、SLF4J；**不依赖任何 rag 模块**。
- 坐标建议：`com.jjx.customer:agent-framework:0.0.1-SNAPSHOT`（命名待定，见 §16.5）。
- 业务侧消费：本地开发 `mvn install` 后按版本依赖；后续推私有仓库（Nexus）或 JitPack
  （本仓 pom 已配置 jitpack 仓库，可直接引用 GitHub Release 坐标）。

### 16.4 与落地批次的关系

`B0`（脚手架）先行，之后 B1-B5 的框架侧改动全部落在新项目里；业务侧只保留
"实现 SPI + 注册绑定"的改动。旧框架代码（现 `chat/agent/core|workflow|slot`）在 B5 收敛时删除。

### 16.5 待定

1. ~~新项目落点~~ → **已定**：`D:\04_projects\agent-framework`（独立目录，与 `27_llm-observability` 同级）。
2. ~~项目命名与坐标~~ → **已定**：`com.jjx.customer:agent-framework:0.0.1-SNAPSHOT`，模块
   `platform-agent-framework-core` / `platform-agent-framework-engine` / `agent-framework-spring-boot-starter`。
3. 构建方式：`JAVA_HOME=ms-21.0.11`、`mvn -o -s .mvn-settings.xml clean test`（离线、走本机仓库）。
4. B1-B3 期间的开发副本位于业务仓 `.codex-scratch/agent-framework/`（因沙箱写权限所致），
   框架工作收尾后删除，外部目录为唯一真身。
