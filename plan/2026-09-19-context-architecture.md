# 上下文与会话生命周期设计 — 2026-09-19

> 状态：**设计阶段，未落地**。本文档汇聚 2026-09-19 讨论的结论、依据与待定项，作为跨会话交接的权威说明。
> 关联：`plan/2026-09-19-human-in-the-loop.md`（同日的 HITL 落地）、`plan/2026-09-12-module-split-progress.md`
> 标注约定：**【确认】**= 读过代码/调用点；**【推断】**= 由代码结构推导；**【待验证】**= 无依据，需数据

---

## 一、问题诊断

### 1.1 同源判断

系统用**一个"会话"概念同时承载了三件事**：对话容器（conversation）、一次执行（attempt）、挂起状态（suspend state）。三者生命周期本该不同，却共用 `"ops-" + conversationId` 这一个标识（`OpsRunner.engineSessionIdOf:202-204`）。

于是每个模块都按自己的理解解释"会话结束了没"：

| 模块 | 判断依据 | 结果 |
|---|---|---|
| 引擎 | Session 状态 `COMPLETED` | 重建，上下文全丢 |
| 业务 | `sa_agent_session` TTL 60 分钟 | 还能恢复 |
| 预算 | 挂在引擎会话上 | "收尾归零"是重建的**副作用**，非显式语义 |
| 上下文 | 跟着引擎走 | 重建即全丢 |

**四套答案，且没有任何一处显式声明过"一次诊断"的边界在哪。** 下面所有"错位"都不是各自写错了，是同一件事被四个地方各解释了一遍。

### 1.2 物证

`sa_agent_session` 一张表同时装了两个生命周期：

```
Task 级:    slots, summary, autonomy_level, chain_trace_id, status
Attempt 级: stage, missing_slots
```

---

## 二、现状核查【确认】

### 2.1 四个模块的生命周期

| | 创建点 | 权威存储 | 更新时机 | **销毁** | 跨 attempt |
|---|---|---|---|---|---|
| 引擎 Session | `DefaultContextManager.createSession` | `ops_engine_session`，逐节点 checkpoint **覆盖写** | 每节点后 | **永不** | 重建（同 id 覆盖） |
| Workspace | 同上，`new` 空壳 | 无（见 2.4） | — | **永不** | 重建 |
| 引擎 Slot | `slotsSchema().applyDefaults()` | `ops_engine_slots`，覆盖写 | 每轮 `Input.slots` 合并 + checkpoint | **永不** | 重开换新容器 |
| 业务 Slot | `saveAwaitingUser` | `sa_agent_session.slots` | **仅 CLARIFY 挂起时** | 随业务行 | 重开时作预填 |

引擎 Session 状态机 6 态：`CREATED / RUNNING / SUSPENDED / COMPLETED / FAILED / CANCELLED`（`SessionState.java:4-16`）。

### 2.2 三场景（当前代码的落点）

**场景 A：新会话 → 新 task**：全新 conversationId → 全新 `ops-<id>`。干净。

**场景 B：旧会话 → 新 task**：⚠️ **无落点**。与场景 C 走完全相同的判断，会被当成"上一个诊断的补充信息"合并进去。

**场景 C：旧会话 → 旧 task → 追问上一轮结论**：⚠️ 分裂且用户不可见——

| 上一轮结束时 | 引擎状态 | 追问时发生 |
|---|---|---|
| 挂起中（卡片未答） | SUSPENDED | resume，**scratchpad + 阶段产出 + 槽位全在** |
| 已出结论 | COMPLETED | `startSession` 重建，**上下文全丢**，从零重排 |

### 2.3 多实例

- 内存 miss 走 DB 兜底，**能正常 resume**（`DefaultContextManager.load:114-121`）——这点是好的
- 缓存优先且**不对账**（`:110-113`），A 实例按陈旧 state 决策
- 两个 store **无版本无锁** last-writer-wins，陈旧内存态 persist 会整份覆盖新快照
- 首轮并发（无 AWAITING_USER 行可 claim）**两个实例可同时 startSession**，内存态永久分叉
- 唯一跨实例互斥是业务表 `claim`（`AgentSessionMapper:17-19`），只对已有挂起行成立

### 2.4 已确认的缺陷

**静默错误类**

| # | 位置 | 后果 |
|---|---|---|
| 1 | `PgEngineSlotStore.load:62-73` catch 后返回 empty；`DefaultContextManager.load:117` 为 `ifPresent` | 取不到快照就用**空槽位** resume：预算归零、scratchpad/阶段产出/`pending_ask` 全丢；`pending_ask` 丢失使 `ActExecutor` 幂等重入分支被跳过 → 输出"流程结束但未产出结论" |
| 2 | `PgEngineSlotStore.save:51-59` 同 | 快照写失败无声 |
| 3 | `StartOptions.defaults()` 的 `ephemeral=false`，`asEphemeral()` 全仓零调用 | REST 每次调用生成随机 UUID 会话，写两张引擎表，**永不删除**（与注释声明矛盾） |

**状态机类**

| # | 位置 | 后果 |
|---|---|---|
| 4 | `claim` 后若本轮未执行到 `OpsRunner`（如 `AgentBranchDispatcher:60-64` DegradeGuard 拒租约直接 return） | 行**永久停在 RUNNING**；`findActive`/`expire` 都只看 AWAITING_USER |
| 5 | 业务 TTL 只清业务态 | 业务判"无会话"，引擎仍 SUSPENDED → 下一轮 `OpsRunner:115` **resume 一个业务层已遗忘的诊断** |
| 6 | `CANCELLED` + `cancel()` 零调用者 | 前端"停止"走流取消，**不触达引擎** |

**资源类**

- `release()` / `delete()` / `PersistenceManager.delete` **全仓零调用**，无 `@Scheduled`
- 四个进程内 map + 两张引擎表**只增不减**
- `sa_agent_session` 的 AWAITING_USER 行无人再问则**永不过期**（TTL 仅在 `findActive:50-54` 惰性判断）
- **Workspace 是只写不读的死数据**：生产未传 `snapshotStore` → `EngineBuilder:516-518` 兜底为进程内实现；`load()` 从不读快照；业务代码**零消费** `fs()/memory()/vectorStore()`

**上下文类**

- `ActExecutor:169-171`：`Thought` 截到 120 字符，`Observation` **全文**进 scratchpad——**压缩优先级是反的**

### 2.5 两个"为持久记忆设计但从未启用"的设施

- `sa_agent_session.summary` —— 注释写明"会话结论（终态时回填）"（`AgentSessionState.java:19`），`OpsRunner.clarifyStateOf` 传 `null`
- `Workspace.memory()` —— 接口上就是"长期记忆"，恒空壳且持久化链断裂

设计者显然想过这件事，只是没接上。

---

## 三、目标设计：四层结构

### 3.1 每层只回答一个问题

```
Conversation   ──  记忆的封闭域     「什么可以被一起记住」   ← 硬边界：不跨会话
  └─ Topic     ──  相关性的边界     「什么应该一起进上下文」
       └─ Task  ──  工作的边界     「什么算解决了」
            └─ Attempt ── 执行的边界  「一次能烧多少预算」
```

这四层不是拼的，是四个**互不重叠**的问题。之前所有错位，根源都是有人试图用一层的边界回答另一层的问题。

### 3.2 Task 是通用层，不是可选层

RAG 的"查一下 invoiceCode 的取值规范"也是一个 Task。**结构统一，策略可声明**：

| | RAG | 诊断 |
|---|---|---|
| Attempt 数 | 恒 1 | 1..N |
| 允许挂起 | 否 | 是 |
| 产出 | Answer（= `kind=answer` 的 finding） | Findings |
| 关闭条件 | 答出来 | 结论被接受 |

**反例警戒**：把 Task 做成 RAG 可选的，等于把"四套答案"的病根重新种回去。

### 3.3 Attempt 必须是一等持久化实体【争议点】

与"Attempt 降级为 Task 内部状态"的主张分歧。保留理由：

- **三重隔离**：预算隔离（"追问预算不算诊断预算"是硬需求）、scratchpad 隔离、挂起状态隔离（`SUSPENDED` 是 attempt 属性）
- **内核的 `Session` 就是 Attempt**，已是独立 id / 独立状态机 / 独立持久化表 / 独立预算
- RAG 的 `attempt=1` 是**策略**，不是结构缺失

> 注：本方保留此判断的理由**不是**"内核已经这样做了"（那是保守主义，已收回），而是内核状态模型与组织性状态的形状不匹配（见第七节判据）。

### 3.4 四层与存储的对应

| 层 | 存储 | 现状 |
|---|---|---|
| Conversation | `sa_conversation` / `sa_message` | 已有 |
| Topic | **新表** + `TopicEdge` | 新建 |
| Task | **新表**（吸收 `sa_agent_session` 的 Task 级字段） | 新建 |
| Attempt | `ops_engine_session` / `ops_engine_slots` | 已有，**id 派生规则要改** |

**最根本的改动**：`"ops-" + conversationId` → `"ops-" + taskId + "#" + attemptNo`。不做这个，上面所有设计都没有落地位置。

---

## 四、上下文设计

### 4.0 核心命题：上下文是计算出来的

**上下文不是被写出来的，是被计算出来的。** 系统设计的不是"上下文长什么样"，而是"上下文如何被计算出来"。

```
真相源（不可变）→ 状态（可查询）→ 投影（纯函数·有版本）→ 组装（预算·保真）→ 上下文
                                                              ↓
                                                         组装记录（审计）
```

三条不变式，本节所有设计都由它们推出：

| 不变式 | 含义 | 直接后果 |
|---|---|---|
| **计算而非存储** | 上下文是运行时视图，不持久化 | 一致性问题**不存在**（不是"被解决了"）——异步物化是在给它制造问题 |
| **有界而非全量** | 组装成本 = O(关联数据量) ≠ O(会话历史量) | 必须有 `ORDER BY … LIMIT`，不能靠"数据恰好不多" |
| **留痕而非覆盖** | 真相源 append-only，视图可重建 | 审计链完整、schema 变更可重投影 |

**限定**：上下文的**事实部分**是计算的；**指令部分**仍然是被设计的（见 §4.9）。

### 4.1 组装公式

```
context(新 attempt) =
    user.preferences                  // 用户偏好，轻量
  + topic.summary                     // 本主题导航摘要（结构化）
  + task.findings[active]             // 本任务已确立主张 + 证据指针
  + linkedTopics[*].summary           // 显式关联主题（仅 confirmed 边）
  + attempt.scratchpad                // 本次执行过程
  ✗ 其他会话的内容                     // 永不
  ✗ 其他主题的原始消息                 // 永不
  ✗ 未确认的隐式关联                   // 永不
```

- **Topic 是默认相关性单位**——主题内全部相关，用压缩摘要即可
- **跨主题必须显式建边**，不做隐式相似度召回（不可解释、会漂移、审计说不清）
- **永远不带原始消息**，`sa_message` 是 ground truth，不是上下文

### 4.2 三层职责分离

| 层 | 内容 | 是否默认注入 |
|---|---|---|
| **导航层** `topic.summary` | 结构化 JSON：有哪些 Task、各自结论、未决假设、实体 | ✅ |
| **证据层** `finding.evidence[]` | 结构化指针：类型/来源/ID/时间戳/置信度 | ✅（只带指针） |
| **原始证据** | 日志原文、检索分片 | ❌ 按需解引用 |

**主题摘要是导航，不是压缩后的真相。** 结构化而非自然语言：

```json
{
  "topic_id": "...",
  "title": "发票红冲失败",
  "entities": ["/api/invoice/reverse", "invoiceCode"],
  "tasks": [
    { "id": "t1", "goal": "为什么失败", "status": "closed",
      "findings": ["invoiceCode 必填未传"] },
    { "id": "t2", "goal": "正确报文", "status": "open", "findings": [] }
  ],
  "open_questions": ["是否影响其他接口"],
  "linked_topics": ["topic_b"]
}
```

**推论**：因为 summary 是**派生投影**而非存储的散文，"把 Task 从 Topic A 移到 B"是廉价操作（重新投影即可，不需要"摘要的摘要"）。

### 4.3 主题关联：显式边

```
TopicEdge {
  from, to,
  kind: related | supersedes | depends-on,
  status: proposed | confirmed | rejected,
  createdBy: rule | model | user
}
```

- **只有 `confirmed` 的边参与上下文组装**；`proposed` 只用于 UI 提示
- 建边三来源，按可靠性排序：
  1. **规则（确定性）**：共享实体——**同 traceId 最强**；同接口/同错误码为弱边
  2. **模型提议**：检测到共享实体时生成**建议**（不自动注入），用户确认后建边
  3. **用户显式**：直接 confirmed
- **弱规则边只拉 summary，不拉 findings**，把强边留给用户与 traceId

### 4.4 Findings 状态机

- **只追加，不物理删除**
- 状态：`active | superseded | retracted`
  - `superseded`：被新结论取代（正常演进）
  - `retracted`：被判定为错（否定）
  - **两者审计含义不同，不可合并**
- 组装只注入 `active`
- 新 finding 与旧 finding 冲突时触发 HITL 确认
- **模型不能单方面 retract 用户/规则来源的 finding**

### 4.5 Topic 的定义：相关性，不是领域实体

**必须钉死**，否则组装会自相矛盾：

| 定义 | 「下单超时 → 根因在订单、触发在库存、关联配置」算什么 |
|---|---|
| Topic = 相关性/话语单元 | **一个** topic |
| Topic = 领域实体 | 三个 topic |

取**相关性**定义。按领域实体切会把多服务根因的诊断撕成三个 topic，恰在最需要上下文连续时切断。

**跨领域现象由 Finding 上的实体标签表达**（`entities: [...]`），不靠切分 Topic。

### 4.6 Task 与 Topic 的关系

- **Task 归属唯一 primary Topic**（发起时的主题）
- Task 的过程**可引用多个 Topic 的 findings**（经显式边）
- **写回**：本 Task 产出对其他 Topic 有意义的 finding 时，**经确认**写回关联 Topic

### 4.7 预算分层

```
Conversation.budget     ← 会话总预算
  Topic.budget          ← 软上限，超出触发摘要压缩
    Task.budget         ← 耗尽时挂起等用户决策
      Attempt.budget    ← 单次执行（现状：maxLlmCalls=24, timeout=300s）
```

- 耗尽策略 = **挂起 + 请求用户确认**，**绝不静默截断**（对齐 `ActExecutor:150-159` 现有原则）
- **注意口径差异**：现有预算是 **LLM 调用次数**（`llm_calls`），上表是 token 预算——两套机制，需要明确采用哪套或并存
- **不预设数字**：单次诊断实测规模为 12 轮工具循环（inv 4 / res 5 / ver 3）+ 24 次 LLM 调用；token 级预算只在 Topic/Conversation 层才有意义，而该层**无数据**

### 4.8 User Scope（隐式维度）

```
User Scope（隐式，跨会话）
  └─ Conversation（记忆封闭域）
       └─ Topic → Task → Attempt
```

**不直接进上下文组装**，只影响：

- 知识库检索的过滤条件（租户隔离）
- 用户偏好注入
- 历史经验召回

**注意**：User Scope 引入后，"记忆的封闭域"不再是 Conversation 唯一——存在两个记忆域：

| 记忆域 | 类型 | 内容 |
|---|---|---|
| Conversation | 情景记忆 | 这次工作发生过什么 |
| User Scope | 程序/偏好记忆 | 这个人是谁、偏好什么 |

与语义记忆（知识库，非"记忆"而是"已确认的知识"）三足鼎立。

---

### 4.9 上下文源：机制统一，治理分离

上下文由两类源组成，**共用同一个组装器、同一条组装记录，但版本独立治理**。

| 源 | 内容 | 变更驱动源 | 版本跟谁走 |
|---|---|---|---|
| **投影源** | `f(状态, as-of) → 内容` | **数据结构 / 状态结构变了** | schema |
| **指令源** | prompt 资产（可参数化） | **对模型行为的期望变了** | 模型调优 |

**划界判据**：不是"它是否被计算出来"（按那个标准静态模板会被吸收成"无状态投影"，看似自洽但无用），而是**"它的变更由什么驱动"**。新增一种 finding 类型 → 改投影；输出改成四段式 → 改模板。两者触发原因、评审重点、验证方式都不同，**合并版本号会让无关变更互相牵连**。

**为什么必须统一机制**：审计对象是模型最终看到的**那一个完整 prompt**。两套渲染会让"为什么看到了 X"要查两处。**审计的完整性来自记录，不来自版本号合并。**

**现状证据**：`OpsPrompts.compose(body, tools, scratchpadSlot)` 已经是这个形状——同一方法里拼「资产正文」（指令）与「从 stage 定义派生的工具行」（投影），机制统一，而资产治理只覆盖 `body`。本设计是为既有做法命名，不是新引入。

### 4.10 三个时点（组装频率）

| 时点 | 频率 | 内容 |
|---|---|---|
| **装配期**（启动时） | 一次 | 资产正文 + 结构派生段（工具白名单） |
| **attempt 期**（每次 attempt 开始） | **1 次 / attempt** | findings / topic 投影（查库 + 按预算裁剪） |
| **调用期**（每轮 LLM 调用） | N 次 / attempt | `{{slots.*}}` 插值（scratchpad 每轮都变） |

真正的投影是 **per-attempt 一次**，不是 per-call——这放宽了（但不取消）§4.11-③。

### 4.11 投影的五条约束

1. **保真等级** —— 不是所有内容都能被投影：
   - `verbatim`：逐字节，禁改写（用户原始报文、日志原文摘录）
   - `structured`：精确结构化（traceId、错误码、接口名）
   - `summarized`：可压缩可损失（诊断历程、已关闭 task 的结论）

   **`verbatim` 部分不可压缩，只能整体取舍**——被改写一字节的报文比缺失的报文更危险。
2. **确定性** —— 同状态必算出同上下文。管住三处：`ORDER BY` 需全序（稳定 tiebreak）、依赖时间处必须带显式 **as-of 时间戳**、组装期间需快照隔离。
3. **纯计算** —— 投影中**绝不能含 LLM 调用**。需要 LLM 的压缩/摘要必须在组装路径之外（这是异步化唯一正确的位置）。
4. **失败可区分** —— 投影失败必须与投影为空区分。**组装器遇到投影失败必须响亮失败，不得产出"降级上下文"**（同 §2.4 缺陷 1 的同一类错误）。
5. **版本治理** —— 投影规则版本必须进 `AgentFingerprint`：

   ```java
   // 现在：只覆盖模板
   new AgentFingerprint(agentId, workflowId, promptHash)
   // 应为：
   new AgentFingerprint(agentId, workflowId, promptHash, projectionRuleVersion)
   ```

   这是本设计对现有代码的**唯一必改点**，且只需加一个字段。

### 4.12 组装记录（审计）

每次组装落一条：

```
{ attemptId, topicId, taskId,
  included: [{source, count}],
  skipped:  [{source, reason, count}],
  projectionRuleVersion, templateVersion,
  assembledTokens }
```

一行，成本可忽略，同时服务三件事：**审计**（为什么带了这段）、**Topic 切分调优**（哪次切错导致该带的没带）、**预算调参**。

### 4.13 逃生舱

若每加一点上下文都必须"建表 + 写投影规则 + 注册进组装器"，团队早晚会绕过它——**绕过之后审计链就断了，而那正是做这套东西的理由**。

因此保留**显式直通通道**（static passthrough）：允许声明"此内容无状态依赖，直接注入"，但**必须在组装记录里标记来源**。逃生舱可以有，但必须是**被治理的**，不是暗门。

---

## 五、边界与切分

### 5.1 Topic 边界

- 按**领域实体变化**切：接口变了、traceId 变了 → 强信号
- 与当前主题语义无重叠 → 候选
- 用户显式切换 → 直接切
- **切分点可纠正**，用 HITL 确认卡片

### 5.2 可纠正性的具体机制

- **静默切换 + UI 可见标记 + 可撤销**（不是每次弹窗确认——太烦）
- 允许把 Task 从 Topic A 移到 B，**自动重建边与摘要**（因为摘要是派生投影，成本低）

### 5.3 冷启动

**第一条消息即建 Topic，Task 与之同时创建**，不留"未归属消息"状态——否则组装规则要处理空档。

### 5.4 Task 边界

按**目标**切。同一主题内目标变更 = 新 Task：

```
Topic「发票红冲失败」
  ├─ Task 1  为什么失败        → findings: invoiceCode 必填未传
  ├─ Task 2  正确的报文是什么   → findings: 完整报文
  └─ Task 3  怎么避免再发生     → findings: 校验前置
```

三个 Task 共享一个 Topic，因此**上下文天然连续**，不需要建任何边。

### 5.5 Attempt 失败后的归属

**失败不关 Task**，只关闭 attempt，Task 回到 `open`——"查不出来"通常正是需要再试一次的情形。

---

## 六、跨会话：沉淀，而非记忆

不跨会话是硬边界，但"用户下次开新会话问同一个接口"是真实需求。解法是**沉淀**而非记忆泄漏：

```
会话内：Topic / Task / Findings  ── 有状态、可撤销、短期
会话之间：知识库 / 接口目录        ── 无状态、已确认、长期
                 ↑ 人工确认后"提升"
```

一次诊断里确认过的正确报文，**经人工确认后提升为接口目录条目**，下次任何会话都能通过语义记忆命中。

| | 记忆 | 知识 |
|---|---|---|
| 状态 | 有状态、可撤销 | 无状态、已确认 |
| 时效 | 短期（会话内） | 长期 |
| 作用域 | Conversation | 全局/租户 |

**收益**：避开跨会话记忆必然带来的权限、多租户、冲突、过期问题。

---

## 七、内核演进清单

### 7.1 判据

> **内核只包含"所有 agent 都需要的执行原语"，且这些原语的状态不需要被跨执行查询。**

| 概念 | 执行原语？ | 状态需跨执行查询？ | 归属 |
|---|---|---|---|
| Session / Slots / Workflow / Node / Tool / Prompt | ✅ | ❌ | 内核 |
| **Workspace** | ❓ | **✅** | **出内核** |
| **Topic / Task** | ❌ | **✅** | 业务层 |
| **Findings / Edges** | ❌ | **✅** | 业务层 |

### 7.2 硬理由：状态模型的形状不匹配

内核的状态载体是 **`Slots` —— 按 sessionId 存的 JSON blob 快照**（`ops_engine_slots`，`session_id PK + snapshot JSONB`）。读写方式是"整份取出、整份写回"，**不能查询、不能跨执行聚合**。

而 Topic/Task/Findings 的本质需求恰好相反：查询 active findings、聚合共享实体、按时间检索撤销历史。

**塞进 slot blob 会逼内核长出一套关系模型——那它就不是执行引擎了。** 这与"内核可独立成仓"的定位直接冲突。

### 7.3 内核真正的债（与 Topic/Task 无关）

| 债 | 位置 | 建议 |
|---|---|---|
| **Workspace** | 零使用、只写不读、永不回收、持久化链断裂 | **建议删除** |
| **存储层静默降级** | `PgEngineSlotStore.save/load` catch+warn | **响亮失败**："读不到状态"与"没有状态"必须可区分 |
| **生命周期契约缺失** | `SessionStore.delete` 零调用，无 reaper SPI | 内核提供**契约**，策略留给消费方 |
| **消息双事实源** | `SessionRecord.messages` vs `sa_message` | 明确哪份权威 |

---

## 八、落地顺序（依赖关系，非选项）

1. ~~**修静默降级**——`PgEngineSlotStore` 的 load/save、`#4 RUNNING 死状态`~~ ✅ **已完成 2026-09-19**，见 §十一
2. **内核瘦身**——删 Workspace、补生命周期契约（可与 1 并行）
3. ~~**引入 Task 实体 + 改会话 id 派生规则**~~ ✅ **子步 1-4 已完成 2026-09-19**（记录见 `plan/2026-09-19-task-layer-design.md` §十）。⚠️ **缺测试覆盖**——OpsRunner 的 Task 逻辑未经验证
4. **Topic 层**（取决于第 3 步稳定）
5. **Findings 台账**——**先拿 2-3 个真实诊断跑一遍验证粒度，再定表结构**
6. 清理/回收、并发约束（同一 Task 同时只有一个 in-flight attempt）、reaper

---

## 九、未验证 / 待定

| 项 | 状态 |
|---|---|
| **Topic 切分准确率** | **【待验证】** 领域实体信号（接口/traceId）有把握；语义切分准确率完全未知，必须真实会话验证 |
| **Findings 粒度** | **【待验证】** 只有发票报文一个案例。同一次诊断产生几条 claim、`kind` 怎么分，设计阶段只能猜 |
| **预算口径** | 未定：`llm_calls`（次数）还是 token？现有是次数 |
| **Topic 层优先级** | 库内 201 个会话以单主题为主（测试数据，非真实分布）。设计按"未来必然复合"取提前量，但**无真实数据支撑** |
| Attempt 是否一等实体 | 本文持保留意见（§3.3），与"降级为 Task 内部状态"的主张分歧未消 |
| 内核是否重写 | 本文主张**不重写 Session 模型**（形状是对的），只做 §7.3 四件事 |

---

## 十、本次会话已修（2026-09-19）

| 改动 | 文件 |
|---|---|
| 上下文来源段被前端重复渲染（`md.render` 与 `contextRows` 渲染同一段） | `frontend/src/components/ChatView.vue` —— 抽 `contextBlockStart()`，渲染前剥离 |
| `retrieve_knowledge` 参数名漂移：手写 hint 写 `topK`，schema 实为 `topN` → 模型照传被判 `unknown argument` | `OpsDiagnosisStages.java` —— 改回 schema 派生 |
| `ToolLine.hint` 死字段（上述 bug 的根源） | `OpsDiagnosisStages.java` —— 删除，`tools` 改为 `List<String>` |
| 缺 schema 描述时静默往 prompt 写 `- null` | `ToolLoopStageModule.java:133-144` —— 改启动即失败 |
| prompt「——」规则的错误理由 | `prompts/workflow/ops_diagnose_v2/{resolve,verify}.md` |
| 四段式交付格式（排查结论/证据链/修正动作/风险提醒） | 同上两文件（**已入 prompt，未跑真机验证**） |

验证：`platform-business` 全量测试 69 passed / 0 failed（`mvn -pl platform-business -am test`）。

---

## 十一、落地记录：第 1 步（2026-09-19 完成）

**目标**：让「读不到状态」与「没有状态」可区分。原实现两者都返回 `Optional.empty()`，
调用方拿**空槽位**继续 resume，`pending_ask` 一并丢失使 `ActExecutor` 的幂等重入分支被跳过，
最终吐出「诊断流程结束但未产出结论」——**看起来像正常收尾，实则是坏掉的输出**。

| 改动 | 文件 | 说明 |
|---|---|---|
| 槽位读/写失败上抛 | `PgEngineSlotStore.load/save` | 原 catch+warn 返回 empty / 静默；改 ERROR + `IllegalStateException`（带 sessionId） |
| 会话读/写失败上抛 | `PgEngineSessionStore.load/save` | 同上。读失败返回 empty 会让调用方以**全新会话重开一个其实存在的诊断** |
| **加载原子化** | `DefaultContextManager.load` | ⚠️ 连锁问题：原实现**先 `sessions.put` 再 `slotStore.load`**，槽位读失败时缓存里留下"没有槽位的会话"，后续 `load` 命中缓存返回、`slots()` 又 `computeIfAbsent` 造空容器——**静默退回空状态，使上面的修复失效**。改为先取快照再写缓存 |
| `RUNNING` 死状态 | `AgentSessionMapper.release` + `AgentSessionServiceImpl.release` + `ResumeCoordinator.release` + `ChatOrchestrator` | 抢占（AWAITING_USER→RUNNING）后若本轮没执行到 `OpsRunner`（降级闸拒租约 / 抛异常），行永久停在 RUNNING，而 `findActive`/`expire` 都只看 AWAITING_USER → 会话被静默遗弃。恢复分支加 try/finally 释放；成功时是空操作（状态已被写成 AWAITING_USER/DONE） |
| 回归测试 | `EngineStoreFailureTest`（7 例） | 同时守住两个方向：**失败必须上抛**，且**"无数据"仍须返回 empty**——防过度修正把合法语义一并改坏 |

**保留的降级**：`AgentSessionServiceImpl` 的「失败一律 warn + 降级」**未动**——其类注释明确该降级
"只影响恢复追问体验，不阻断对话主链路"，实测成立（业务会话丢失后引擎侧仍能 resume）。
本步只针对**会把"读失败"静默转成"空状态"从而产出伪结论**的路径。

**未覆盖**：进程被杀（kill -9 / 部署重启）导致的 RUNNING 残留——需 TTL 级回收，归入第 6 步。

**验证**：`platform-business` 全量 76 passed / 0 failed（`mvn -pl platform-business -am test`）。

---

## 十二、落地记录：Findings 台账（第 5 步，2026-09-19）

### 12.1 关键发现：抽取是**纯解析**，不需要模型

第 5 步的前置是"先拿真实诊断验证粒度"。用真机产出的结论（`sa_message` id=1284）分析后发现：

**四段式交付格式（§十 已修）意外地把结论变成了可解析结构**——`证据链` 段本身就是「取值（来源）」列表，
`修正动作` 是 JSON + 改动表。于是主张抽取从"再叫一次模型来归纳"退化成**字符串切分**：
零模型调用、零延迟、可单测。

真实样本粒度：**约 3-6 条主张/次诊断**（1 根因 + 1 修正动作 + N 风险，实测 N=3），比预想的粗得多，
表结构可以很简单。

### 12.2 实现

| 文件 | 作用 |
|---|---|
| `task/AgentFinding.java` | 契约：`Kind`(ROOT_CAUSE/CONSTRAINT/FIX/RISK/ANSWER) + `Status`(ACTIVE/SUPERSEDED/RETRACTED) + `Evidence(label,value,source)` |
| `task/FindingExtractor.java` | 四段式解析（纯字符串，无模型） |
| `task/entity/AgentFindingEntity.java` + `mapper/AgentFindingMapper.java` + `AgentFindingServiceImpl.java` | 持久化（**只追加**，Mapper 不提供删除方法） |
| `sql/init.sql` | `sa_agent_finding` 建表 |
| `OpsRunner` | 出结论即抽主张并落库；追问时把生效主张装进 `prior_findings` 槽 |
| `IntakeStageModule` | 声明 `prior_findings` 槽 |
| `OpsPrompts.compose` | 新增「上一轮已确立的主张」段（一处生效，不必改三个 prompt 文件） |

**两个状态语义必须分开**（设计如此，实现照做）：
`SUPERSEDED` = 被新结论取代（正常演进，`replaceActiveWith` 自动标）；
`RETRACTED` = 被判定为错（用户否定）。混成一个会丢掉"当时是演进还是纠错"的审计信息。

**注入有界**：`renderFindings` 限 10 条、每条 400 字符，超出时显式写明省略条数——
对应 §4.0「有界而非全量」不变式。宁可少带，不可让上下文随对话轮数无上限增长。

### 12.3 测试（夹具取自真实生产输出）

`FindingExtractorTest`（9 例）用**真机结论原文**（含服务端追加的上下文段）当夹具，而非手造理想文本——
手造夹具会掩盖模型实际书写格式的偏差。首版即失败并暴露一处真实的不一致：

> 模型写来源时**有时带 `来源：` 前缀有时不带**（同一份输出里「接口」条不带、「时间」条带）。
> 解析器需归一化，否则 `source` 字段两种形态混杂。

同时覆盖降级：非四段式文本不硬抽（宁缺勿滥）、缺段只抽存在的部分、null/空串不抛异常。

### 12.4 未做

| 项 | 说明 |
|---|---|
| ~~用户否定 → `markRetracted`~~ | ✅ **已接线**，见 §12.5 |
| CONSTRAINT 类型 | 枚举已留，抽取暂未从「修正动作」的改动表里提炼字段级约束 |
| Topic 层（第 4 步） | ⚠️ **经真机数据判定：当前会退化成 1:1，暂缓**，见 §12.6 |

### 12.5 主张变更：让主张能被**精确**否定（2026-09-19）

**为什么不是"加一行 markRetracted 调用"**：`HumanResponseInterpreter` 只被图内节点
（`ReplanExecutor` / `ConfirmExecutor` / `AskMissingExecutor`）调用，是**挂起恢复路径**的东西；
而场景 C 的用户异议走的是 `OpsRunner` 的**新 attempt 路径**，完全不过它。所以需要一个新机制。

**做法：语义判断交给模型，精确落位交回解析**（与四段式抽取同一套路）

```
注入时编号     - [#3] [RISK] 环境为 prod，且 /api/invoice/reverse 为不可逆操作…
模型结论里     **主张变更**
               - #3 否定：用户指出 invoiceNumber 唯一，不存在重复红冲
抽取器         FindingExtractor.deniedIndices() → {3}
              → markRetracted(第3条.findingId)
```

**为什么用编号而非文本相似度**：模糊匹配迟早会把"哪条更像"判错，而**错标一条主张比不标更糟**——
它会把一条其实成立的结论记成"被推翻"。编号越界一律丢弃并记日志，绝不猜。

**顺序不可颠倒**：`applyDenials` 必须在 `replaceActiveWith` **之前**。反过来的话，
那条被否定的会被 `supersedeActive` 一并标成 `SUPERSEDED`——**"被更新"与"被推翻"是两种不同的
审计事实**，混了以后复盘就分不出当时是演进还是纠错。

**该段可选**：模型没输出（或没读懂用户意图）时返回空集，主张照常被整体取代，只是少了这层语义。
降级路径不产生错误状态。

**顺带修的解析健壮性问题**：`section()` 原以"下一个**特定**段标记"为结尾，模型漏段时会吞并后续段落
（缺「证据链」时，「排查结论」会把变更段、风险段一起吃进去）。已改为"下一个**任意**段标记"。

**真机验证（2026-09-19，通过）**：用户发「#5 这条我不同意：调用方为何取到空值不在我们职责范围内」，
跑完一轮后库中状态：

```
SUPERSEDED | attempt=1 | 6 条
RETRACTED  | attempt=2 | 1 条   ← 正是 #5 那条（"建议核查调用方为何取到空值…"）
SUPERSEDED | attempt=2 | 5 条   ← 其余 5 条正常演进
ACTIVE     | attempt=3 | 2 条   ← 新结论的主张
结论含「主张变更」段：有
```

**设计承诺的三件事全部成立**：模型输出结构化变更段 → 抽取器按编号精确落位 →
`RETRACTED` 与 `SUPERSEDED` 在审计上可区分。用户说"这条我不同意"时，
系统记住的不再只是"结论变了"，而是**"哪一条被推翻、其余是被更新"**。

### 12.6 ⚠️ Topic 层暂缓：真机数据证实它会退化成 1:1

设计里 Topic 按**相关性**分组、Task 按**目标**切分。但实际约束是：

- Task 切分的唯一触发是**强信号**（换 traceId / 换接口）——本轮的 `ResumeCoordinator` 实现
- 强信号 = 换了领域实体 = **按定义就是另一个 Topic**
- "同一实体内换目标"（弱信号）**刻意未做**（`task-layer-design.md` §四 自评的致命风险）

⇒ **新 Task ⟺ 不同实体 ⟺ 不同 Topic**，Topic 与 Task 一一对应。

这正是 `task-layer-design.md` §九 自标的风险："若 Topic 也是按目标切，Task 与 Topic 可能退化成一对一"。
**风险成真。** 现在建 Topic 表它会是空转的抽象——与 Task 同样多行、零条边。

**结论**：Topic 层的前置是**同实体内按目标切分 Task**。在它之前，跨任务的上下文关联
用 **task 之间的 `related` 边**表达即可（那才是"有些主题会关联"真正需要的机制）。
何时做 Topic：等目标级切分有了真实案例与准确率数据之后。

---

## 十三、落地记录：回收与并发（第 6 步，2026-09-19）

### 13.1 并发那半早已完成

`claim` / `beginAttempt` 都是**单条原子 UPDATE**（行锁 + WHERE 状态条件），
已在真实 PG 上验证：并发第二刀抢到 **0 行**。所以本步只做回收。

### 13.2 回收：`TaskReaper`（`@Scheduled`）

两件事，各自独立失败：

| 任务 | 解决什么 |
|---|---|
| **全局清扫过期任务** | 惰性 TTL 只在会话**被再次访问**时清它自己——**再也没人访问的会话永远留着**。尤其进程被杀留下的 `RUNNING`（`release` 只在异常路径执行，崩溃时不走），会让该任务永远看起来"有 attempt 在跑" |
| **回收引擎执行态** | 删除终态任务各 attempt 的引擎会话与槽位快照（scratchpad / 阶段产出 / 游标） |

**回收的是原始执行态，不是诊断结论**：结论已沉淀在 `sa_agent_finding`（结构化主张）
+ `sa_message`（原文）+ `sa_agent_trace`（轨迹），**审计链不断**。

**默认关闭**（`rag.chat.agent.engine-retention-hours` 未配置或 ≤0）：删除是破坏性操作，必须显式开启。
关闭时**仍扫描并打印可回收条数**，便于先观察再决定。

**新增列 `engine_reclaimed`**：不标记的话同一批终态任务每轮都会被取出来再走一遍
"查无此行"的空删除——扫描量随历史无限增长。

### 13.3 ⚠️ 验证中发现的一个交互（已记入设计）

**保留期从"任务终止"起算，不是从"最后活动"起算。** 因为 `abandonStale` 会把 `update_time`
刷成 `NOW()`，刚被清扫的任务自然不满保留期。于是：

```
有效保留时长 = sessionTtl（60min，判定过期）+ engineRetentionHours（回收执行态）
```

这不是 bug，但**配两个参数时要一起算**，否则会以为"设了 72h 却 60 分钟就不见了"。

### 13.4 验证

全部 SQL 在真实 PG 上以 `BEGIN…ROLLBACK` 验证：

- `abandonStale(60)` → 只清 120 分钟未动的，刚活动过的保持原状
- `findReclaimable(72h)` → 只取已终态 + 超期 + 未回收；10 小时前的未被取到
- `markEngineReclaimed` 后再查 → 0 行（不重复扫描）
- `CONCLUDED` **不在可回收范围**（等用户反应，引擎态随时要被追问用上）
- `attemptCount=3` → 正确推导出 `ops-<taskId>#1/#2/#3`

### 13.5 未做

| 项 | 说明 |
|---|---|
| ~~内核侧内存回收~~ | ✅ **已完成**，见 §13.6 |
| **`sa_message` / `sa_agent_trace` 归档** | 同属"只增不减"，但它们是审计主体，不该按时间删——需要的是冷热分层，属独立议题 |

### 13.6 内核侧内存回收：契约早就有，是没人调

**更正一条早先的判断**：设计文档里多次写"内核需补 evict 契约"——**不准确**。
`ContextManager.release(sessionId)` 本就移除 `sessions`/`slots`/`workspaces` 三个 map，
只是**全仓零调用**。缺的不是 API，是消费者。

**但接之前先修了一处自相矛盾的副作用**：`release` 原实现会顺带写一份工作区快照：

```java
// 原实现（已移除）
if (workspace != null && snapshotStore != null) {
    snapshotStore.save(workspace.snapshot("release-" + System.currentTimeMillis()));
}
```

而默认实现 `InMemoryWorkspaceSnapshotStore` 是按 workspaceId **无限追加**的进程内 map，
且 `WorkspaceSnapshotStore.load/latest/list` **全仓零调用**——快照**只写不读**。
于是"释放内存"实际是"把数据从三个 map 搬到一个只增不减的 map 里"。

已移除该行。**依据**：`release` 的语义是"释放"，持久化不该是它的副作用；
且本仓业务从不写工作区（恒空壳），存进去的是空快照。
`DefaultContextManager.snapshotStore` 字段保留（内核公开构造签名不动），已标注当前不写它——
`PersistenceManager.snapshotWorkspace` 仍是该存储的消费方（虽也零调用，但那是**显式 API 能力**，
不会自己触发，与热路径上的自动写不同）。

**接线**：`TaskReaper` 删库后调 `agentEngine.contexts().release(sessionId)`，
进程内对象一并摘掉。只对终态任务做——`CLOSED`/`ABANDONED` 不会被 resume，摘缓存无副作用。

**验证**：`platform-business` 109 + `platform-agent-core` 161 全绿（内核那 161 项尤其关键——
本次动了内核行为）。

### 13.7 ⚠️ 验证陷阱：时区差点让我误报一个不存在的 bug

**应用侧连接时区是 `Asia/Shanghai`**（JVM 默认时区驱动）——证据：`sa_agent_task.update_time`
（Java `LocalDateTime.now()` 写）与 `sa_message.created_at`（SQL `DEFAULT NOW()` 写）**同一时刻取值一致**，
都是北京时间。应用**内部自洽**。

**但 `docker exec … psql` 的会话时区是 `Etc/UTC`**（服务器 `TimeZone` GUC 默认值）。于是手查时间条件时：

```sql
-- 在 UTC 会话下：命中 0 行
SELECT count(*) FROM sa_agent_task
WHERE status IN ('OPEN','RUNNING','SUSPENDED','CONCLUDED')
  AND update_time < NOW() - interval '60 minutes';
-- 还算出过负数：extract(epoch from now()-update_time) = -445 分钟
```

看起来像"TTL 从不生效 / 回收器没跑"，**而实际是任务还没到期**。差一点据此报一个不存在缺陷。

**规矩**：用 psql 验证任何时间相关逻辑前，先 `SET TIME ZONE 'Asia/Shanghai'`。
或保证夹具与比较**都在同一会话内用 `NOW()` 构造**（那些验证是有效的，两边同源）。

### 13.8 回收首跑的可观测性

回收器的产出**只有日志**，没有库内副作用（`abandonStale` 无到期任务时改 0 行、
`findReclaimable` 在回收关闭时只打一行计数）。所以"它跑了没有"从库里看不出来。

已在库中放置探针任务 `reaper-probe-0001`（`SUSPENDED`，静默 3 小时），
下一轮扫描应将其清扫为 `ABANDONED`——那是回收器行为的**唯一可观测证据**。
验证完建议删掉该探针行。

### 13.9 一个待定的设计问题：TTL 也作用于 `CONCLUDED`

`abandonStale` 的 WHERE 含 `CONCLUDED`，因此**出结论 60 分钟后任务会被判过期**。
用户隔两小时回来说"结论不对"时，任务已 `ABANDONED`，`findActive` 找不到 → 开新任务 → 上下文断裂。

这与本次改造的目标（追问能接上）存在张力。两种取向：

- 保持现状：TTL 是统一的"多久算这次对话结束了"，配大即可（`rag.chat.agent.session-ttl-minutes`）
- 区分对待：`CONCLUDED` 用更长的窗口（"等用户反应"本来就允许更久）

**未决**，留给真实使用数据判断——若真出现"隔天追问丢上下文"的反馈，再调。

### 13.10 补上回收器的单测（抽窄接口做接缝）

初版 `TaskReaper` 无测试，障碍是它依赖的 `AgentTaskMapper` 继承 MyBatis-Plus `BaseMapper`
（二十多个方法），手写替身不现实。抽了 `TaskReaperStore`（三个方法的窄接口）做接缝——
`AgentTaskMapper` 继承它，实现侧零额外代码。

同时把内存释放的依赖从 `Engine`（24 个方法）收窄到 `ContextManager`（10 个），
新增 `@Bean agentContextManager(Engine)` 暴露。**窄依赖既是设计意图也是可测性要求。**

`TaskReaperTest` 6 例，钉的是**编排**（SQL 覆盖不到、最容易写错的部分）：

| 用例 | 钉什么 |
|---|---|
| 回收未启用时只报数 | **默认路径绝不能删任何东西**，也不能标记（标了就永远轮不到它） |
| 开启回收 | 按 attempt 逐个删会话+槽位并释放内存（只删库不摘缓存 = 回收做了一半） |
| attempt 数为 0/null | 不推导会话，但任务仍标记（避免每轮重复扫描） |
| 清扫失败 | **不阻断回收**——"两段各自独立失败"是设计承诺，得有测试守 |
| 查询失败 | 不上抛、不猜着删 |

**验证**：`platform-business` 115（109+6）+ `platform-agent-core` 161 全绿。

### 13.11 ⚠️ 启动失败：`@MapperScan` 会把「包」下所有接口都注册成 mapper

**现象**（用户实机启动）：

```
Parameter 0 of constructor in ...TaskReaper required a single bean, but 2 were found:
    - agentTaskMapper:  …/task/mapper/AgentTaskMapper.class
    - taskReaperStore:  …/task/mapper/TaskReaperStore.class
```

**根因**：`@MapperScan("com.jjx.customer.platform.**.mapper")` 是按**包**注册的——
该包下**每个接口**都会成为 mapper bean，与是否标 `@Mapper` 无关。
我把 `TaskReaperStore` 这个**端口接口**放进了 `mapper` 包，于是它凭空多出一个实现。

**修复**：把端口接口**移出被扫描的包**（`task/mapper/` → `task/`），
而**不是**加 `@Qualifier` 绕过——`@Qualifier` 会让"端口误放 mapper 包"这件事看起来正常，
下次还会有人踩。约定即：**`*.mapper` 包里只放 MyBatis mapper**。

**规范检查**（全项目 21 个 `*.mapper` 接口，移动后全部是真 mapper）：

```bash
find . -path ./node_modules -prune -o -name "*.java" -print | grep -E "/mapper/" | grep -v "/target/"
# 逐个确认：extends BaseMapper（否则会被 @MapperScan 误注册）
```

**教训：这类装配冲突原本没有测试能拦。** 全仓唯一的装配测试 `ChatOrchestratorWiringTest`
用的是**最小上下文**（协作方全 mock，不启动数据库/Redis/Web），覆盖面只有它自己那一个注入点。
**Spring 的 bean 歧义只在 refresh 时暴露**——也就是说，改完装配必须真启动一次。
本轮 `platform-business` 115 项全绿，但应用起不来，就是这个原因。

### 13.12 补上守卫：`MapperPackageConventionTest`

**做法：源码级断言，不启动上下文。** 遍历各 Maven 模块 `src` 下所有 `mapper` 目录的 Java 文件，
凡是**接口声明**却不继承 `BaseMapper` 的，一律点名失败。

**为什么不用上下文测试**：能拦住 bean 歧义的上下文测试必须加载真实配置
（数据源、Redis、Web……），重且脆；而本次的失败模式**有确定性的静态特征**——
"`*.mapper` 包里出现了非 mapper 接口"。断言这个特征便宜、快（0.2s）、且覆盖全部模块。

**只遍历 `platform-★/src`**，不扫全仓：仓库里有 `.git`（百 MB 级）与 `node_modules`（数千条目），
全量遍历既慢又无意义——Java 源码只可能在模块 `src` 下，顺带天然避开 `target/`。
（首版扫全仓耗时 3.0s，收窄后 0.23s。）

**守卫必须验证它会失败**——从没失败过的守卫不算已验证。做法是造一个真实的违规文件
（`DeliberatelyBadPort`：一个非 mapper 接口放进 `mapper` 包），确认它被点名：

```
[ERROR] Tests run: 1, Failures: 1
  platform-business/src/main/java/…/task/mapper/DeliberatelyBadPort.java
```

确认后删除该文件。**留下的不是"一个通过的测试"，而是"一个已知会失败、且失败信息可操作"的守卫。**

---

## 十四、清尾：可取消、压缩、消息归属、孤儿引擎行（2026-09-20）

### 14.1 `Engine.cancel()` 接通（原本零调用）

**原状**：`Engine.cancel(Session)` 存在但**全仓零调用**。交付层的"停止生成"只做三件事——
落部分输出、关 SSE、记日志（见 `SseDeliveryPort` 注册的 Runnable），**引擎完全不知情**。

**光接线没用**：`cancel()` 只是 `state(CANCELLED) + persist`，而运行中的 run 随后会 `applyOutcome`
把状态覆盖掉。所以做了三件事：

| # | 改动 | 说明 |
|---|---|---|
| 1 | **`DefaultWorkflowRuntime` 加节点边界取消检查** | 正在执行的节点打断不了，但**不会再开下一个节点**，也不会把中止结果当正常收尾 |
| 2 | **`OutcomeKind.CANCELLED` 单独立值** | 原先只映射 `FAILED`，`CANCELLED` 会落进 `DIRECT` → 把中止记成 `CONCLUDED`、抽出空主张、甚至交付"诊断完成（无结论文本）"。单独立值后编译器**强制** `AgentBranchDispatcher` 处理该分支 |
| 3 | **`OpsRunner.cancelDiagnosis(conversationId)` + `ChatController` 接线** | 置引擎会话为取消态，并把任务放回可恢复态（不放会停在 RUNNING，下条消息被"已有请求在执行中"挡住直到 TTL） |

`OpsRunner` 对中止轮**不落结论、不抽主张**——中止就是没有结论。

### 14.2 压缩优先级调正

`ActExecutor` 原先把 `Thought`（模型推理，体积小）截到 120 字符，却让 `Observation`
（工具产出，**最占体积**）全文进 scratchpad——**最该压的没压**。

现在 `Observation` 截到 4000 字符，且**显式标注截断**并给出重查指引
（"请缩小查询范围后重查"）。静默截断会诱导模型基于残缺证据下结论；
标注后模型知道还有内容没看到，而 traceId / 时间窗都在槽位里，重查成本远低于背着全文。

### 14.3 消息归属：以 `sa_message` 为准

`SessionRecord.messages` 与 `sa_message` 曾并列为"消息的两份记录"，**没有声明哪份权威**。

实测**没有任何 prompt 资产使用 `{{messages}}`**——引擎那份在本仓是执行内数据。已在
`SessionRecord` 类注释新增「消息归属」一节声明边界：对话内容以调用方为准（本平台 `sa_message`，
带引用溯源/澄清卡片等引擎不认识的业务字段）；引擎 messages 只在单次运行内自洽，用于模板渲染，
**不要**拿它当对话历史回放。

### 14.4 又一处 `ephemeral` 泄漏（比之前修的那个更严重）

修 ops 单轮时说"REST 每次调用都在无限写引擎表"——**漏了知识线**。`KnowledgeRunner:156` 用的
正是 `agentEngine.run(agentId, input)` 这个内部走 `StartOptions.defaults()`（ephemeral=false）的重载，
而它是**高频路径（每次提问都走）**。已改为显式 `startSession + asEphemeral()`。

复查全仓：`run(agentId, input)` 只剩 `AgentFramework`（SDK 门面，Spring 应用不经过），生产路径无残留。

### 14.5 存储问题的实测反转：`sa_message` 不是问题

原本打算给 `sa_message` / `sa_agent_trace` 做冷热分层。**先量了一遍，前提被推翻**：

| 表 | 行数 | 总大小 |
|---|---|---|
| **`ops_engine_slots`** | 4,175 | **76 MB** ← 真正的大户（约 18 KB/行） |
| `ops_engine_session` | 4,175 | 12 MB |
| `sa_agent_trace` | 317 | 3.8 MB |
| **`sa_message`** | 813 | **1.1 MB**（48 天 / 17 条每天 ≈ **8 MB/年**） |

`session_id` 形态分布揭穿来源：

```
纯随机 UUID（ephemeral 未生效的产物）  4141
ops-<conversationId>（旧格式）            29
ops-<taskId>#<n>（新格式）                 5
```

**09-18 一天就产生 3907 条**（某次批量/评测）。这些孤儿**不挂在任何 Task 上**，
所以按 Task 反推的回收**永远找不到它们**。

### 14.6 孤儿引擎行回收（`TaskReaper` 第三阶段）

`findOrphanEngineSessions(retentionHours, batchSize)`：`NOT EXISTS` 匹配
`'ops-' || task_id || '#' || n`（n 取遍 attempt 序号）。

**安全性论证**：保留期内不动；而超过保留期仍活着的任务，早被 `abandonStale`（TTL 60 分钟）
判成终态了。所以这里删掉的，要么属于已终止的任务，要么根本无主。

**真实数据验证**：

```
孤儿(超24h)           = 4008     ← ephemeral bug 的产物
对应槽位存储           ≈ 53 MB   ← 可回收
仍受保护的新格式会话    = 5       ← 不会被误判 ✓
```

72 小时窗口下当前只算得出 45 个——09-18 那批距今才 36 小时，**到期后自然落入可回收范围**。

**注意**：回收默认关闭。要真正清掉这 53 MB，需显式设置
`rag.chat.agent.engine-retention-hours`（保留期从"任务终止/最后活动"起算，见 §13.3）。

**验证**：`platform-business` 119 + `platform-agent-core` 161 全绿。

### 14.7 ⚠️ 更深的根因：`ephemeral` 从未真正生效过

§14.4 修了"知识线没传 `asEphemeral()`"，但**真机验证时发现它没用**——问一个知识问题，
引擎会话数照样 4175 → 4176 → 4177。

**根因不在调用方，在内核**：

```
ContextManager.persist()          ← ephemeral 检查在这里（早退）✓
PersistenceManager.checkpoint()   ← 直接写 store，绕过检查  ✗
DefaultWorkflowRuntime            ← 每个节点后都调 checkpoint（4 处）
```

`ephemeral` 只挡住了 `startSession` 那一次**初始**持久化，而运行期**每个节点的 checkpoint
照样落库**。所以这个标志**从未真正生效**——§14.4 的修复方向对，但不足以生效。

**这解释了库里看到的全部现象**：4175 个会话中 4141 个是随机 UUID，占 `ops_engine_slots` 约 76 MB——
正是这些"本该不落库"的单轮请求留下的。

**修法**：把 ephemeral 判定从 `ContextManager` 接到 `PersistenceManager` 上
（`EngineBuilder` 装配期注入 `contexts::ephemeral`），`checkpoint` 先判再写。
未装配时不跳过——**默认行为逐字不变**。

**守卫** `EphemeralPersistenceTest`（3 例），含一条**反向断言**：普通会话必须照常落库，
别把两者一起挡住——只测"临时会话不落库"的话，把 `checkpoint` 整个改成空操作的错误实现也能通过。

**方法论教训（值得记住）**：

> 这个缺陷**单测发现不了**。代码逐行看都对——`createSession` 会加进集合、`persist` 会早退。
> 只有**发起一次真实请求、然后数库里的行数**才会暴露。
> 单测验证"函数按它被要求的方式工作"，验证不了"整条链路是否真的这么走"。

这也是本轮唯一一个靠"起真实应用 + 观察副作用"才挖出来的内核缺陷。
