# Agent 内核 / 诊断业务 / 数据流转 流程图

> 代码为准（2026-09-21）。注意：CLAUDE.md 决策 4 中的 `ToolLoopEngine` / `StageSpec` / `LoopExitChecks` / `JsonToolCallAdapter` 等类名在当前代码中**已不存在**（属旧业务层内核），本图按真实落点绘制。

## 1. 总览：一条用户消息如何分流

```mermaid
flowchart TD
    P["POST /chat/stream"] --> CO["ChatOrchestrator.execute()"]
    CO --> D1{"ResumeCoordinator.findResumable<br/>该会话有活动任务?"}
    D1 -->|"命中 SUSPENDED / CONCLUDED"| RT["按 task.agentId 路由<br/>空白 / 未知 → ops"]
    D1 -->|"无"| D2{"RouteEvaluator<br/>规则短路 · traceId 直查"}
    D2 -->|"显式 agentChoice<br/>或 意图 needs_diagnose ≥ 0.6"| AB
    D2 -->|"默认"| RAG["RAG 主线<br/>KnowledgeRunner → 多通道检索 → 生成"]
    RT --> AB["AgentBranchDispatcher<br/>DegradeGate 租约 · SSE 预告"]
    AB --> OPS["OpsRunner.run(question, slots, conversationId)"]
    OPS --> CORE["agent-core Engine"]
    CORE --> OUT["OpsAnswer(kind, text, trace, humanRequest)"]
    RAG --> PORT["SseDeliveryPort"]
    OUT --> MAP{"RunOutcomeMapper.kindOf"}
    MAP -->|SUSPENDED| CL["emitClarify 结构化卡片<br/>+ sa_message.clarify"]
    MAP -->|DIRECT| DI["emitDirect 流式 + 落库"]
    MAP -->|ESCALATE| ES["emitEscalate 升级卡"]
    MAP -->|CANCELLED| CAN["emitCancel"]
    CL --> PORT
    DI --> PORT
    ES --> PORT
    CAN --> PORT
```

## 2. 内核分层结构（platform-agent-core）

```mermaid
flowchart TB
    subgraph ENTRY["engine.core · 入口"]
        E["DefaultEngine.execute()<br/>守卫 → 过滤 → 拦截 → 工作流 → 落库 → 追踪"]
        EB["EngineBuilder / EngineServices<br/>24 个协作者装配"]
    end
    subgraph DEF["definition · 定义"]
        WD["WorkflowDefinition<br/>nodes · edges · slotsSchema · regions · dynamicPolicy"]
        HRQ["HumanRequest CLARIFY / DECIDE / APPROVE / CONFIRM<br/>HumanResponse 协议 #decision: #override: #fill:"]
    end
    subgraph RUN["engine.workflowruntime · 运行时"]
        WRT["DefaultWorkflowRuntime.run()<br/>游标驱动节点主循环"]
        REG["NodeExecutorRegistry<br/>按 NodeType / 按名字 双索引"]
        NODES["Llm · Tool · Human · Condition<br/>Parallel / SubWorkflow · Custom"]
    end
    subgraph CTX["engine.contextmanager · 上下文"]
        CM["DefaultContextManager<br/>Session / Workspace / Slots 三个 map + ephemeral 集合"]
    end
    subgraph PERS["engine.persistence · 持久化"]
        PM["PersistenceManager.checkpoint()"]
        ST["SessionStore · SlotStore<br/>CheckpointStore · WorkspaceSnapshotStore"]
    end
    subgraph CROSS["crosscutting · 横切"]
        TOOL["ToolProvider → ToolRegistry → ToolExecutor"]
        GUARD["Guards · MiddlewarePipeline · EventBus · Tracer"]
    end
    E --> WRT
    EB -.-> E
    WD --> WRT
    HRQ -.-> NODES
    WRT --> REG --> NODES
    WRT --> CTX
    WRT -.->|"每推进一步"| PM
    PM --> ST
    NODES --> TOOL
    E --> GUARD
    WRT --> GUARD
```

## 3. 运行时：节点主循环与出口判定

```mermaid
flowchart TD
    A["cursor = entryNode<br/>checkpoint.save（初始）"] --> B{"nodeId 为空?"}
    B -->|是| DONE["RunOutcome(COMPLETED)"]
    B -->|否| E1{"visited ≥ maxSteps<br/>或 state == CANCELLED?"}
    E1 -->|"是"| FAIL["RunOutcome(FAILED / CANCELLED)"]
    E1 -->|"否"| EXE["invokeNode<br/>BEFORE 守卫 → span → AROUND 拦截链 → 执行器 → AFTER 过滤"]
    EXE --> LB{"LoopBreakException<br/>Guards.MaxIterations?"}
    LB -->|"是"| BRK["写 metadata.loopBreak<br/>中断后只许走出 Region"]
    LB -->|"否"| MERGE["合并 slotWrites<br/>Region 短名 → prefix_name 展开 · messages · usage"]
    BRK --> MERGE
    MERGE --> CONV{"loop.exit 节点<br/>delta &lt; minDelta?"}
    CONV -->|"是"| BRK2["convergenceBreak 收敛出口"]
    CONV -->|"否"| SUS
    BRK2 --> SUS{"NodeResult.suspended?"}
    SUS -->|"是"| SUSP["checkpoint.save<br/>RunOutcome(SUSPENDED) ⏸"]
    SUS -->|"否"| NXT["resolveNext<br/>动态边(白名单) &gt; 显式 next &gt; 静态出边(priority + 条件 + guard)"]
    NXT --> HAS{"next == null?"}
    HAS -->|"是"| DONE
    HAS -->|"否"| ADV["cursor.advanceTo(next).arriveVia(edge)<br/>checkpoint.save → 两表 UPSERT"]
    ADV --> B
```

## 4. 诊断业务图：ops_diagnose_v2

```mermaid
flowchart TD
    START["OpsRunner.execute<br/>ensureTask → beginAttempt → startSession → engine.run"] --> EX
    EX["extract_slots · SlotExtractExecutor<br/>1 次 LLM 抽槽 · 口语时间 → ISO 窗口"] --> AR
    AR["auto_resolve · AutoResolveExecutor<br/>三层补全：规则 → 日志反查 → LLM 推断"] --> GATE
    GATE{"auto_gate<br/>intake_confirmed == 1 且 missing_count == 0 ?"}
    GATE -->|"missing_count &gt; 0"| CS
    GATE -->|"齐备但未确认"| CF
    GATE -->|"已确认"| I1
    CS["collect_slots ⏸ A<br/>AskMissingExecutor · CLARIFY 卡"] --> RG{"slots_regate<br/>ops-max-intake ≤ 3"}
    RG -->|"仍缺"| CS
    RG -->|"齐备"| CF
    CF["confirm_slots ⏸ B<br/>ConfirmExecutor · CONFIRM 卡"] -->|"#decision:confirm"| I1

    subgraph INV["INVESTIGATE ≤ 4 步 · 查日志定位"]
        I1["inv_think<br/>investigate.md 输出 JSON 意图"] --> I2["inv_act · ActExecutor<br/>get_time · query_logs · retrieve_knowledge"]
        I2 --> I3{"inv_decide<br/>has_calls &gt; 0"}
        I3 -->|"是"| I1
    end
    I3 -->|"否"| RP1["replan_1 ⏸ C · ReplanExecutor<br/>四态：continue / adjust / ask_human / escalate"]
    RP1 -->|"adjust（额度 1）"| I1

    subgraph RES["RESOLVE ≤ 5 步 · 生成 / 纠正报文 · 出口护栏"]
        R1["res_think"] --> R2["res_act<br/>retrieve_knowledge · validate_request（按接口 schema）"]
        R2 --> R3{"res_decide"}
        R3 -->|"是"| R1
    end
    RP1 -->|"continue"| R1
    R3 -->|"否"| RP2["replan_2 ⏸ C' · ReplanExecutor"]
    RP2 -->|"adjust"| R1

    subgraph VER["VERIFY ≤ 3 步 · streamThink 流式外发"]
        V1["ver_think<br/>四段式：结论 / 证据链 / 修正动作 / 风险"] --> V2["ver_act"]
        V2 --> V3{"ver_decide"}
        V3 -->|"是"| V1
    end
    RP2 -->|"continue"| V1
    V3 -->|"否"| CONC["conclude · ConcludeExecutor<br/>最后非空阶段产出（ver→res→inv）+ 自动补全段"]
    I2 -->|"ask_user ⏸ D"| I2
    I2 -->|"escalate / 预算触顶"| ESC
    RP1 -->|"escalate / 额度耗尽"| ESC
    RP2 -->|"escalate / 额度耗尽"| ESC
    ESC["escalate_node<br/>OutcomeKind = ESCALATE"]
    CONC --> OUT["RunOutcome → OpsAnswer"]
    ESC --> OUT
```

挂起点：**A** 缺必填槽 · **B** 待确认 · **C/C'** replan 决策移交 · **D** 阶段内 `ask_user`（任一阶段 act 均可）。统一落 `NodeResult.suspended` + `pending_human_request`。

## 5. 挂起 → 恢复 时序

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant O as ChatOrchestrator
    participant R as OpsRunner
    participant E as Engine 内核
    participant N as 挂起节点
    participant D as SseDeliveryPort
    U->>O: orderNo X 报错了，最近 10 分钟
    O->>O: ResumeCoordinator.findResumable
    O->>R: run(question, slots, convId)
    R->>E: startSession(attemptIdOf(taskId, n))
    E->>N: invokeNode
    N->>N: 缺必填槽 → 写 pending_human_request 槽
    N-->>E: NodeResult.suspended（不推进游标）
    E->>E: checkpoint：ops_engine_session + ops_engine_slots = SUSPENDED
    E-->>R: RunOutcome(SUSPENDED)
    R->>R: markSuspended(sa_agent_task: stage / slots)
    R-->>D: OpsAnswer(kind = CLARIFY, humanRequest)
    D->>U: SSE clarify 卡 + sa_message(clarify JSON)
    Note over U,E: ——— 用户回复 ———
    U->>O: traceId 3b5f… 帮我看看
    O->>R: 命中 SUSPENDED → resume 路径
    R->>E: contexts().load(attemptId) → engine.resume
    Note over R,E: 先读槽位快照再入内存缓存；claim 抢占防并发
    E->>N: 重入同一节点
    N->>N: HumanResponseInterpreter 配对 pending → 落槽 → 清空 pending
    N->>E: 继续后续节点（逐节点 checkpoint）
    E-->>R: RunOutcome(COMPLETED)
    R->>R: markConcluded + applyDenials + FindingExtractor
    R-->>D: OpsAnswer(kind = DIRECT)
    D->>U: SSE message 流式 + sa_agent_trace
```

## 6. 槽位：来源与流转

```mermaid
flowchart LR
    Q["用户原话"] --> EX["extract_slots<br/>LLM 抽槽（只填空缺）"]
    EX --> P["SlotProvenance 台账<br/>存 inferred_slots JSON"]
    R1["规则：相对时间换算 · 接口路径正则<br/>InterfaceCatalog 唯一命中 · 目录缺省值"] --> P
    R2["日志反查 LogBackfill<br/>关键字 + 时间窗 → query_logs → traceId 精查"] --> P
    R3["LLM 高置信推断<br/>L2 ≥ 0.7 / L3 ≥ 0.5"] --> P
    P --> R1
    P --> R2
    P --> R3
    P --> CARD["HumanRequest.SlotAsk<br/>值 + 来源角标 + 依据 + 候选值"]
    CARD --> USR["用户：点选 #decision: / #override:slot=value<br/>或自由文本 → HumanResponseInterpreter"]
    USR -->|"白名单 + 按当前值调和"| P
    P --> SLOT["引擎槽位<br/>确认后 → 进阶段 prompt"]
    subgraph AUT["AutonomyPolicy：目录声明 ∩ 会话档位（只能收紧）"]
        L1["L1 多问"]
        L2["L2 默认"]
        L3["L3 少问"]
    end
    AUT -.-> R2
    AUT -.-> R3
    AUT -.-> USR
```

来源状态机：`rule → default → llm → log_query → user_override（终态）`。

## 7. 数据落库与流转

```mermaid
flowchart TB
    subgraph M["内存态 · DefaultContextManager"]
        MS["Session + Cursor<br/>sessionId · state · cursor.nodeId"]
        MSL["Slots 全量<br/>业务槽 · trace_id · llm_calls · scratchpad<br/>inferred_slots · pending_human_request"]
    end
    subgraph DBE["引擎表 · PgEngineSessionStore / PgEngineSlotStore"]
        ES[("ops_engine_session<br/>record JSONB = SessionRecord")]
        ESl[("ops_engine_slots<br/>snapshot JSONB = SlotSnapshot")]
    end
    subgraph DBB["业务表 · init.sql"]
        TK[("sa_agent_task<br/>status · stage · slots · autonomy_level · attempt_count")]
        FD[("sa_agent_finding<br/>claim · evidence · status ACTIVE / SUPERSEDED / RETRACTED")]
        MG[("sa_message<br/>content · citations · clarify JSON")]
        TR[("sa_agent_trace<br/>trace_id · steps · message_id")]
    end
    MS -->|"checkpoint：每推进一个节点 1 次"| ES
    MSL -->|"同上"| ESl
    ES -.->|"resume：load 回填"| MS
    ESl -.->|"resume：先读槽再入缓存"| MSL
    MSL -->|"markSuspended：投影目录内业务槽"| TK
    TK -.->|"prefill：task.slots ∪ 本轮槽"| MSL
    FD -.->|"prior / related_findings → prefill 槽 → prompt"| MSL
    MSL -->|"结论四段式 → FindingExtractor 纯解析"| FD
    MSL -->|"交付 emitDirect / emitClarify"| MG
    MG -->|"recordTrace"| TR
    TK -.->|"TaskFollowUpQa：answer / retrieve / rerun"| TK
    REAP["TaskReaper · 60 min<br/>abandonStale → DELETE 引擎两表 + release"] -.-> ES
    REAP -.-> ESl
```

| 补充 | 说明 |
|---|---|
| `sa_agent_session` | 已被 `sa_agent_task` 取代，代码零读写，仅存历史行 |
| `ephemeral` | REST 单轮诊断 / 知识问答：`persist` 与逐节点 checkpoint 双处拦截，不落引擎表 |
| 回收开关 | `engine-retention-hours ≤ 0` 时只统计不删 |
| Workspace | 全仓不持久化（`WorkspaceSnapshotStore.load` 零调用） |
| 无 compaction | 等价物是 `ActExecutor.truncateObservation`（观测截断 4000 字符） |
