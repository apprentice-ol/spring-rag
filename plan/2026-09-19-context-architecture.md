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

1. **修静默降级**——`PgEngineSlotStore` 的 load/save、`#4 RUNNING 死状态`。它们是 bug，且会污染对上层行为的观察
2. **内核瘦身**——删 Workspace、补生命周期契约（可与 1 并行）
3. **引入 Task 实体 + 改会话 id 派生规则**——**支点**。涉及 `OpsRunner.engineSessionIdOf`、`ChatOrchestrator` 恢复路由、`ResumeCoordinator`、`sa_agent_session` 整表
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
