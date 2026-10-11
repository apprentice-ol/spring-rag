# 计划归档 · 平台重构（2026-09-12：模块拆分 + Agent×Workflow + Prompt 能力包）

> 2026-10-11 文档整理：本文合并当日四份文档，按「目标态设计 → 执行计划 → 完成记录」排序，原文完整保留。
>
> - Agent × Workflow 目标态设计（讨论收敛稿 v1.0）
> - Workflow 框架化执行计划（P2 重写版）
> - Prompt 能力包执行计划
> - 模块化拆分与分层重构进度（16 模块定稿，跨会话交接记录）

---

<!-- 原文件：docs/plans/2026-09-12-agent-workflow-target.md -->

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
- 本文「Workflow 框架化执行计划（P2 重写版）」（框架化批次 1-2）
- 本文「Prompt 能力包执行计划」（prompt 资产化 P1 / 组合 P2）

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

---

<!-- 原文件：docs/plans/2026-09-12-workflow-framework.md -->

# Workflow 框架化执行计划（2026-09-12，v2 重写版）

> **执行状态（2026-09-13 刷新）**：本计划已全部落地——框架抽为同仓 `platform-agent-framework`
> （当日后续变更：core / engine 两模块**合并为单模块**，包按概念分组，无 core/engine 层级；
> 下文 `Workfolw*` 为当时拼写，现名 `WorkflowStageSpec` / `WorkflowErrorPolicy`）
> （零业务依赖），业务侧 Agent/Workflow 定义在 `platform-business`，编排决策在
> `business.orchestration.ChatOrchestrator`，交付通道在 `platform-delivery`（经 shared 的 `DeliveryPort`/`DeliveryPortFactory` SPI）。
> 下文复选框保留为当时的执行记录；当前结构与判定标准见 `docs/architecture.md` 与
> 本文「模块化拆分与分层重构进度」。

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
- 完整设计（key 空间/4+1 表/两层组合/装配机制/分期）见本文「Prompt 能力包执行计划」

---

<!-- 原文件：docs/plans/2026-09-12-prompt-bundle.md -->

# Prompt 能力包执行计划（2026-09-12 启动）

## 背景与设计契约（三轮讨论定稿，2026-09-12）

诉求 → 机制：

| 诉求 | 机制 |
|---|---|
| prompt 好管理 | 资产化：逻辑 key 与内容版本分离 |
| 修改有历史 | 版本表：只增不改，带 change note |
| 能比对 | 版本间 / 包间 / release 间 / **effective set（绑定合并结果）** 四种 diff |
| 一组 prompt → agent 能力、灵活组合 | 能力包：基座+特化两层 merge，绑定即生效 |

### key 空间约定（命名即分层）

```
agent/common/*       公共区：replan 裁决、回答风格、工具使用规范（基座包主体）
agent/{workflow}/*   骨架特有：agent/ops/* 、agent/inspect/*（特化包主体）
chat/*               链路级：意图分类、query 改写、RAG 回答人格、闲聊
tool/{name}/desc     工具描述（唯一允许回退代码默认值的 key 类）
```

### 数据模型（sa_ 前缀，spring.sql.init 幂等建表）

```
sa_prompt                 id, prompt_key(unique), current_version_id, description
sa_prompt_version         id, prompt_id, version_no, content, change_note, created_at
                          unique(prompt_id, version_no)；不可变只增
sa_prompt_bundle          id, name(unique), description, agent_type(null=通用包)
sa_prompt_bundle_release  id, bundle_id, release_no,
                          items(jsonb: {prompt_key: version_id}), change_note, created_at
                          unique(bundle_id, release_no)；发布即不可变快照
sa_prompt_binding         id, agent_type(unique), base_bundle_id, overlay_bundle_id(null=无特化)
```

### 两层组合（不做继承链，固定两层）

```
绑定(agentType) = ( 基座包名 , 特化包名 )
每次装配：snapshot = merge(基座包当前release.items, 特化包当前release.items)   // 同 key 特化胜出
```

- 基座包（全平台一个）：只装公共区 key；基座发新 release → 所有骨架下次装配自动受益
- 特化包（按骨架/域）：只装骨架特有 key + 需偏离基座的覆盖 key
- 装配校验：`definition.requiredKeys ⊆ merged.keys`，缺哪个报哪个，绝不静默 fallback
  （工具描述除外：缺 key 回退代码 `AgentTool.description()`——代码默认即其基线版本）

### 活包名 / 死指纹（可复现与自动生效的两全）

| 对象 | 存什么 | 语义 |
|---|---|---|
| sa_prompt_binding | 包名对 | **活**——每次装配解析两包各自当前 release 合并 |
| PromptSnapshot 身份 | `基座@rN + 特化@rM` + 内容 hash | **死**——装配那一刻固化 |
| eval run / sa_agent_session | snapshot 指纹（或内容 hash） | 粘住当时的组合，可复现 |
| 答案/意图/语义缓存 key | 内容 hash | 任一层改动 → 缓存自动失效 |

### PromptSnapshot 装配机制（组合的芯子）

```
请求 → 路由确定 agentType/链路
     → 绑定解析（分层链：请求级参数[预留不开放] > 会话粘住的 release > agent 全局绑定 > 基线包）
     → merge 构造 PromptSnapshot（不可变 Map<key,content> + identity + contentHash）
     → requiredKeys 校验
     → snapshot 进 AgentContext（prompt(key) 访问器，缺 key 抛错带 key 名）
     → agent.run() 全程消费快照
身份进三处：trace 首 step（prompt-bundle）、缓存 key、日志
```

两类消费方都吃快照：
- **agent 内**（workflow 型）：ToolLoopEngine / SlotExtractor / ReplanChecker 改 `ctx.prompt(key)`
- **链路级**（pipeline 直用）：意图分类、query 改写、RAG 回答人格、闲聊——bundle 可整链换人格

### 决策记录（不再重议）

- 固定两层 merge，**不做任意深度继承/组合链**（透明性 > 灵活性）
- 工具描述外**不允许 fallback**；全量快照语义
- classpath `resources/prompts/` 降级为**首次种子**：启动缺 key 建 v1；已有 key DB 为准，
  代码与线上内容不一致时 console 标"差异"+ 一键"以代码内容发新版本"（防开发改码被静默压制）
- 请求级选包参数**留位不开放**（前端将来加"包选择器"时启用）
- 不做：审批流、环境 label、按比例灰度、A/B 框架（eval 双轴已覆盖）、新模板引擎
  （PromptTemplateRenderer 够用）
- 模块归属：PromptSnapshot 数据类 + `prompt(key)` 契约随 rag-agent-framework（阶段 4）；
  绑定/版本/bundle 管理与持久化全在业务侧——框架零持久化依赖

---

## P1 资产化（无前置，已完成 2026-09-12）

### #P1.1 表与持久层
- [x] 5 张表 DDL（sql/init.sql 追加，幂等；bundle/release/binding 为 P2 预建）
- [x] entity + mapper（`chat/prompt/` 独立域：entity/mapper/service/controller 对齐现有模式）
- [x] 基线导入（PromptAssetService @PostConstruct）：缺 key 建档 v1；已有 key DB 为准，
      classpath 不一致记差异（warn + `/prompt/code-drift` 接口），不自动建版本
- [x] **PromptStore 顺手升级**：KNOWN_KEYS 硬编码清单 → 目录扫描（PathMatchingResourcePatternResolver），
      新增 prompt 文件自动预加载 + `allKeys()` 暴露——"忘登记"维护债消除，消费方 raw() 行为零变化

### #P1.2 管理 API + console 面板
- [x] `PromptController` 7 端点：keys（含漂移/未建档标记）/ code-drift / versions / version /
      diff（双版本预取）/ 发新版本 / rollback / sync-from-code——key 经 query param 传递
      （promptKey 含斜杠，@PathVariable 无法匹配且编码斜杠被容器默认拒绝）
- [x] 前端 `PromptManage.vue`（`#/admin/prompt` 菜单项"Prompt 资产"）：左 key 列表（搜索/漂移标记）+
      右时间线/内容查看/逐行 LCS diff（+N/-N 统计）/新版本编辑（textarea + change note）
- [x] 回滚 = 以旧版本内容发新版本（时间线一键；历史只增不改语义不变）

### #P1.3 验收
- [ ] 基线导入后 key 全集与 `resources/prompts/` 一致；改动代码 prompt 重启后差异可见 —— 待整体联调
- [ ] 版本链路：编辑→新版本→diff→回滚 全流程可用 —— 待整体联调
- [x] 现有 PromptStore 消费方行为零变化（raw() 签名与语义不变；全量测试绿佐证）
- [x] vue-tsc 通过 + 后端全量测试绿

## P2 组合与解析（前置：workflow 框架化批次 1-2 完成）

### 2026-09-13 增量（console 侧落地，E2E 已验）

- [x] **前端能力包与绑定 UI**（`PromptBundleDrawer.vue` + `api/bundle.ts`）：建包 / fork /
      发布 release（勾选 key 取当前版本快照）/ release 历史 / agent 骨架绑定（基座+特化，
      校验失败行内红字）——建包→发布 r1（28 keys）→绑定 react_loop→解绑 全链路冒烟通过
- [x] **运行链路接线**：`PromptStoreSnapshotSource` 经 `PromptBindingService.overridesFor`
      取绑定 merge 内容覆盖 classpath（无绑定空覆盖，行为零变化；发布新 release 下一请求生效，
      指纹 hash 随内容自动失效缓存）
- [x] **线上新建 prompt key**：`PromptAssetService.createVersion` 放开 classpath 校验
      （key 命名规范校验替代）；`listKeys` 对 DB-only key 安全（codeMissing 标记，不算漂移）；
      `syncFromCode` 对线上 key 报友好错
- 注：快照接线未做会话粘性（`resolveByReleases` 已备未接）；P2.3 缓存联动未做；
  删 bundle 接口不存在（如需 UI 删除再补）
- **冒烟遗留**：库里留有一个「冒烟测试包」（r1，无绑定）——测试数据，可忽略

### #P2.1 bundle 与绑定
- [x] bundle / release 的 service + console API：组包（挑各 key 版本）→ 发布 release → 复制包
- [x] `sa_prompt_binding` 管理：绑定切换（base+overlay），切换时即时校验
      requiredKeys ⊆ merged + 特化包 agent_type 匹配
- [ ] release 不可变：发布后 items 锁定，删 bundle 需解绑前置校验

### #P2.2 PromptSnapshot 与消费点改造
- [ ] `PromptSnapshot`（contents/identity/contentHash）+ `AgentContext.prompt(key)` 访问器
- [ ] `WorkflowDefinition.requiredPromptKeys()`（聚合 slotExtract/stages/replan 三类 key）
- [ ] `PromptBindingService`（业务侧）：分层绑定链解析 → merge → 校验 → 构造快照
- [ ] 消费点改造（动手前 grep `promptStore.raw` 全消费点建清单核对），已知集合：
      ToolLoopEngine / SlotExtractor / ReplanChecker（agent 内）；
      意图分类 / QueryRewriter / chitchat / RAG 回答（链路级——`ChatClientConfig.ragChatClient`
      的 defaultSystem 挂载方式改为运行时传入，注意 prompt cache 稳定性不回退）
- [ ] AgentTrace 首 step 记 `prompt-bundle: 基座@rN + 特化@rM`

### #P2.3 缓存与会话联动
- [ ] 答案缓存（exact）key 拼 contentHash；意图缓存 key 同理
- [ ] 语义缓存 sa_cache_answer 加 prompt_hash 列进命中 WHERE
- [ ] sa_agent_session 加 prompt 指纹字段：追问恢复粘住原组合；指纹对应的 release 已删 →
      回退当前绑定 + trace warn
- [ ] 切换语义：全局切换下一请求生效，进行中流式与既有会话不受影响

### #P2.4 验收
- [ ] 同一骨架绑不同包 → 行为差异可观测（trace 身份不同 + 回答风格实测变化）
- [ ] 基座发新 release → 所有绑定骨架自动生效；特化覆盖仅影响该骨架
- [ ] 改任一层 prompt → 答案/意图缓存自动失效（重放旧答案的隐患消除）
- [ ] 多轮追问会话内组合不漂移；缺 key 的绑定装配即时报错
- [ ] eval knowledge 基准回归无异常漂移

## P3 深化（前置 P2）

- [ ] eval 双轴：eval run 记录 snapshot 指纹，`paradigm × bundle` 对照（sa_eval_run 加列 +
      前端对照面板加轴）——消融实验："严格包比标准包好多少"可量化
- [ ] effective set diff：两个绑定（切换前后 / 两骨架间）的合并快照对比，逐 key 标注来源
      （基座/特化覆盖/回退）
- [ ] 工具描述 key 化：`tool/{name}/desc` 进 key 空间，ToolRegistry 装配回调从 snapshot 取，
      缺省回退代码 description()（同一工具不同包=不同"使用说明书"）
- [ ] 验收：eval 双轴跑批出数；工具描述换包实测模型行为变化

---

## 与 workflow 框架化计划的全局排序

```
workflow 批次 1（框架重写）─┬─→ workflow 批次 2（路由/双形态/legacy/合流）─→ workflow 批次 3（留缝）
prompt P1（资产化，并行）──┘              │
                                          └─→ prompt P2（组合与解析）─→ 阶段 4（rag-agent-framework 抽出）─→ prompt P3
```

阶段 4 前置包含 prompt P2 的原因：PromptSnapshot 契约决定 framework 模块接口形状，先定接口再拆模块。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 链路级消费点改造碰 ChatClient 装配（defaultSystem 运行时化），影响 prompt cache 稳定 | system/user 分离原则保持：资料进 user、规则进 system 的既有设计不破；改造后验证 Langfuse trace 中 system 段稳定 |
| 基线导入与 DB 演进分叉（开发改码 vs 线上改库） | P1 差异面板 + 一键同步；DB 为运行态真相写进 CLAUDE.md |
| 语义缓存加列迁移 | spring.sql.init 幂等 + 存量行 prompt_hash 允许 null（视为不匹配，自然重写） |
| bundle 发布后 key 需求变化（骨架新增 requiredKey） | 装配期校验兜底：缺 key 报错列出，console 绑定页显示覆盖度 |
| 两层 merge 的排障复杂度 | trace 身份 + effective diff（P3）+ 装配错误信息带 key 名与缺失来源 |

---

<!-- 原文件：docs/plans/2026-09-12-module-split-progress.md -->

# 模块化拆分与分层重构进度（2026-09-12 完成）

> 跨会话交接文档：新会话读这一份即可掌握全貌，无需历史对话。
> 结构权威文档与功能记录：`docs/architecture.md`。

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
