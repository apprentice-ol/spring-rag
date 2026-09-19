# Task 层设计（落地第 3 步）— 2026-09-19

> 状态：**设计阶段，未落地**。上游文档：`plan/2026-09-19-context-architecture.md` §八 落地顺序第 3 步。
> 本文只覆盖**第 3 步（引入 Task + 改 id 派生规则）**，不依赖第 4 步的 Topic 层——Topic 只留字段位。
> 标注：**【确认】**= 读过代码；**【待定】**= 需决策。

---

## 一、这一步要解决什么

拆掉 `"ops-" + conversationId` 这个把 **conversation 与 attempt 焊死**的派生规则，引入 Task 层，使：

| 场景 | 现状 | 目标 |
|---|---|---|
| 新会话 · 新 task | 全新建 | 全新建 |
| 旧会话 · 新 task | ⚠️ 无落点，被当成上一个诊断的补充 | 新建 Task，不继承 finding |
| 旧会话 · 旧 task · 追问 | ⚠️ 看引擎是否 SUSPENDED，两种命运且用户不可见 | 沿用 Task，开新 Attempt |

**顺带解决**：追问预算不算诊断预算（不同 Attempt = 不同引擎 Session，预算天然各算各的）。

---

## 二、现状：要改的三处【确认】

```
OpsRunner.engineSessionIdOf(conversationId) → "ops-" + conversationId   ← 要改
OpsRunner.execute:111-143        load 后判 state==SUSPENDED 决定 resume/startSession  ← 要改
ChatOrchestrator:131-136         resumed 分支 → activeSession.slots() 作预填        ← 要改
ResumeCoordinator.findResumable  只认 AWAITING_USER                                ← 要改
```

`sa_agent_session` 一张表装两个生命周期（§1.2 物证），本步把它拆开。

---

## 三、目标结构

### 3.1 实体与 id

```
taskId     = UUID                      ← 无派生，与 conversationId 解耦
attemptNo  = sa_agent_task.attempt_count（自增）
attemptId  = "ops-" + taskId + "#" + attemptNo
```

**为什么 attemptId 派生而非另存字段**：可从 Task 行算出（无需额外列）、id 自带归属信息（`ops-<taskId>#3` 一眼看出是哪个 task 的第几次）、**不同 attempt 的引擎行天然隔离 → 多实例互相覆盖的问题消失**。

**为什么 taskId 不派生**：派生就意味着和 conversation 耦合，而我们正是要拆开这个耦合。追问开新 attempt、换目标开新 task，都不该改 conversationId。

### 3.2 状态机

```
                    ┌──────────────────────────────────┐
                    ▼                                  │
  新建 ──► open ──► running ──► concluded ──► closed ───┘（用户提新目标时旧 Task 关闭）
             ▲          │           │
             │          │           └──► open（用户不认可结论，开新 Attempt）
             │          │
             └──────────┴──► suspended（attempt 挂起，等用户回答）
                            │
                            └──► abandoned（TTL 过期 / 用户放弃）
```

| 状态 | 含义 | 收到新消息时 |
|---|---|---|
| `open` | 目标未达成，无 in-flight attempt | 沿用 |
| `running` | 有 in-flight attempt | **拒绝并提示**（并发保护） |
| `suspended` | attempt 挂起，明确在等用户回答 | **沿用**（resume 该 attempt） |
| `concluded` | **已出结论，等用户反应** | **默认沿用**（见 §四） |
| `closed` | 目标达成 / 用户结束 | 不沿用 → 新建 Task |
| `abandoned` | 放弃 / 过期 | 不沿用 → 新建 Task |

**`concluded` 是本设计新增的关键状态**，它正是场景 C 分裂的根因所在：现状用"引擎是否 COMPLETED"隐式区分，用户看不出、代码也没声明。显式化之后，"出结论"≠"任务结束"。

> 【待定】`concluded` 与 `closed` 是否值得拆成两个状态？拆的理由是"目标是否达成由人判定"，系统只能知道自己出了结论。若认为复杂度过高，可退化为 `closed` + `last_conclusion` 非空标记——但那样"新消息默认解读为对结论的反应"这条行为就没有状态依据了。

### 3.3 表结构

```sql
CREATE TABLE IF NOT EXISTS sa_agent_task (
    task_id         VARCHAR(64)  PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    agent_type      VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    slots           JSONB,
    stage           VARCHAR(64),
    summary         TEXT,
    autonomy_level  VARCHAR(8),
    chain_trace_id  VARCHAR(64),
    attempt_count   INT          NOT NULL DEFAULT 0,
    topic_id        VARCHAR(64),
    create_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    update_time     TIMESTAMP    NOT NULL DEFAULT NOW(),
    close_time      TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_task_conv_status ON sa_agent_task(conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_task_chain_trace ON sa_agent_task(chain_trace_id);
```

字段来源逐条交代：

| 字段 | 来源 | 说明 |
|---|---|---|
| `slots` | 原 `sa_agent_session.slots` | 8 个业务槽，**权威源仍是引擎槽位，这里是挂起投影** |
| `stage` | 原 `sa_agent_session.stage` | attempt 级，但留作 UI 展示（"卡在哪一步"） |
| `summary` | 原字段，一直恒 `null` | 终态回填；**第 5 步 findings 落地后改为其投影** |
| `autonomy_level` / `chain_trace_id` | 原样 | Task 级 |
| `attempt_count` | 新 | 见 §五 原子递增 |
| `topic_id` | 新，**可空** | 第 4 步才填，本步不依赖 |
| `missing_slots` | **不迁移** | 可从 `OpsSlotCatalog` 重算（现有代码 `clarifyStateOf` 就是这么做的，字段本身冗余） |
| `prompt_releases` | **不迁移** | 【确认】本步涉及的读写路径未使用；若他处依赖需单独确认 |

---

## 四、判定逻辑：新 task 还是追问 ★

**这是本步的核心，也是现状最缺的一环。** 判定必须显式，不能靠引擎状态推断。

```
输入：新消息 + 该 conversation 的活跃 Task（status ∈ {open, running, suspended, concluded}）
输出：沿用 Task（可能开新 Attempt） / 新建 Task
```

判定顺序：

```
1. status == running     → 拒绝，提示"上一轮还在跑"（并发保护，见 §五）
2. status == suspended   → 沿用，resume 该 attempt            【确定】
3. status == concluded   → 默认沿用（用户在对结论作反应）      【默认】
                            └─ 命中「新目标信号」→ 新建 Task    【见下】
4. status == open        → 沿用，开新 Attempt
5. 无活跃 Task            → 新建 Task + Attempt 1
```

**「新目标信号」的规则（第 3 条的例外）**：

| 信号 | 强度 | 动作 |
|---|---|---|
| 消息中出现**不同的 traceId** | 强 | 候选新建 |
| 消息中出现**不同的 interface** | 强 | 候选新建 |
| 用户显式表达（"另一个问题"/"顺便看看"） | 强 | 新建 |
| 语义与当前 Task 目标无重叠 | 弱 | **候选**，不自动新建 |

强信号**直接新建**（确定性高）；弱信号**不自动新建**，走 HITL 确认卡片（复用现有 `HumanRequest.DECIDE` 机制）。

**可纠正**：新建 Task 时在 UI 上留可见标记且可撤销（同 `plan/2026-09-19-context-architecture.md` §5.2 的机制）——切错了代价是上下文断裂，必须可回滚。

> 【待定】"语义无重叠"这条弱信号本轮要不要做？不做则第 3 条只有强信号例外，会用"新接口/新 traceId"漏掉换了话题但没换实体的情形。建议**先做规则，模型判定留到有真实案例之后**。

---

## 五、并发与多实例

**同一 Task 同时只有一个 in-flight Attempt。**

用一条原子 UPDATE 取得执行权（替代现有只覆盖 AWAITING_USER 的 `claim`）：

```sql
UPDATE sa_agent_task
   SET status = 'running',
       attempt_count = attempt_count + 1,
       update_time = NOW()
 WHERE task_id = ?
   AND status IN ('open', 'suspended');
-- 影响行数 = 1 才拿到执行权；= 0 说明已有 in-flight，拒绝
```

这条同时完成三件事：**并发互斥**、**attempt 计数递增**、**状态推进**。且因为是单条 UPDATE，多实例下天然正确（对比现状：首轮并发可同时 `startSession`，内存态永久分叉）。

**多实例收益**：不同 attempt 用不同 engine sessionId（`ops-<taskId>#N`），引擎表不再被同 key 覆盖；内存缓存陈旧的作用域被压到单个 attempt 内。

> 【待定】`claim` 的原子性依赖 DB 行锁，但引擎会话本身仍无并发控制。若同一 attempt 被两个实例同时 resume，问题依旧。是否需要 attempt 级锁（如 `running` 状态下记录持有者 + 超时）？建议**先只做 Task 级**，观察是否够用。

---

## 六、迁移路径

分五步，每步可独立验证：

| 步 | 动作 | 可验证点 |
|---|---|---|
| 1 | `init.sql` 加 `sa_agent_task`（幂等 DDL） | 启动建表成功 |
| 2 | 新增 `AgentTaskServiceImpl`（读写新表），`AgentSessionServiceImpl` 保留 | 单测 |
| 3 | `OpsRunner` 改走 Task：`attemptIdOf(taskId, n)` 替换 `engineSessionIdOf(conversationId)` | 三场景手测 |
| 4 | `ChatOrchestrator` resumed 分支 + `ResumeCoordinator` 改读 Task | 三场景手测 |
| 5 | 存量数据迁移 | 见下 |

**存量数据处理**（两块）：

- **业务侧**：`sa_agent_session` 的 AWAITING_USER 行 → `sa_agent_task` 的 `suspended` 行（`taskId` 取新 UUID，`attempt_count=1`）
- **引擎侧**：存量 `ops_engine_session` / `ops_engine_slots` 的 key 是 `ops-<conversationId>`，新格式是 `ops-<taskId>#1` → **需要 UPDATE 改名**，映射关系从业务侧迁移结果取

> 【确认】库内 201 个会话以测试数据为主（用户确认）。若确认可弃，**引擎侧改名可省**——旧 key 永不再被 load，自然废弃，只需定期清理。建议走这条，省一次高风险数据搬迁。

---

## 七、不做什么（边界）

| 不做 | 原因 |
|---|---|
| **不建 Topic 表** | 第 4 步；本步只留 `topic_id` 可空字段 |
| **不建 Findings 表** | 第 5 步，且粒度未验证（只有发票一个案例） |
| **不动内核** | 内核的 Session == Attempt 语义是对的；本步只改业务层派生规则 |
| **不改 RAG 线** | 本步只覆盖 ops；RAG 走 Task 化在结构统一后单独评估 |
| **不做回收 / reaper** | 第 6 步；但 Task 状态机为它留好了依据（见落地下文） |

**"Task 是通用层"是本步的设计约束**（`agent_type` 已入表，不硬编码 ops），但**本步只实现 ops 一条线的接入**。

---

## 八、风险与回滚

| 风险 | 缓解 |
|---|---|
| 判定逻辑切错，把新目标并进旧 Task | 强信号才自动新建；UI 可见标记 + 可撤销 |
| 存量挂起会话在切换瞬间丢失 | 迁移脚本先跑，再切代码 |
| 引擎侧旧 key 残留 | 不搬迁则定期清理（比搬迁安全） |
| 回滚 | 全部为增量改动（新表 + 新服务类），旧表和旧代码路径可保留一个版本周期 |

---

## 九、未定项

| 项 | 说明 |
|---|---|
| `concluded` 独立成状态 vs 退化为标记 | 见 §3.2 待定 |
| 弱信号（语义无重叠）是否本轮做 | 见 §四 待定，建议先不做 |
| attempt 级并发锁 | 见 §五 待定，建议先只做 Task 级 |
| 存量引擎会话是否搬迁 | 见 §六，建议不搬迁 |
| Task 与 Topic 的多对一关系确认 | 待第 4 步——若 Topic 也是按目标切，Task 与 Topic 可能退化成一对一 |
| RAG 线接入时机 | 结构统一后评估；`agent_type` 位已留 |
