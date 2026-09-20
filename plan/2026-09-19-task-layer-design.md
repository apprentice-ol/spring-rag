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

---

## 十、落地记录（2026-09-19）

子步 1–4 已完成；子步 5（存量迁移）与 §四的「新目标信号」**有意未做**，理由见下。

### 10.1 新增

| 文件 | 作用 |
|---|---|
| `task/entity/AgentTaskEntity.java` | 映射 `sa_agent_task`（`@TableId(INPUT)`：task_id 是 UUID 非自增） |
| `task/AgentTaskState.java` | 业务契约 + `attemptIdOf(taskId, n)` 派生规则 + `currentAttemptId()` |
| `task/mapper/AgentTaskMapper.java` | `claim` / `beginAttempt` / `release` / `expire` |
| `task/AgentTaskServiceImpl.java` | 读写 + 惰性 TTL + 档位读取 |
| `sql/init.sql` | `sa_agent_task` 建表（已在真实 PG 上以 `BEGIN…ROLLBACK` 验证语法） |

### 10.2 关键实现决策（三处偏离原设计，均是有意的）

**① 抢占从决策步下移到执行侧。** 原设计沿用旧结构，在 `ResumeCoordinator`（决策链第 0 步）抢占。
改为在 `OpsRunner` **紧挨真正的运行**抢占——这样"占了位却没跑起来"（降级闸拒租约 / 抛异常）
**在结构上不再存在**，`release` 从"必须的补偿"退化为"异常路径的兜底"。
`ResumeCoordinator` 因此变成只读。

**② 出结论落 `CONCLUDED`，不是 `CLOSED`。** "系统给出了结论"≠"目标达成"，后者由人判定。
留成可沿用态，下一轮追问才能挂回同一 Task。

**③ 槽位携带与"是否恢复"解耦。** 旧实现只在挂起恢复时才带槽位；新实现**所有路径都带**
（任务上已确认的 → 本轮传入的覆盖）。这才是"出了结论后说不对，等于从头开始"的真正修复点——
它不需要新数据结构，只需要在 `OpsRunner` 里无条件合并 `task.slots()`。

### 10.3 实现过程中发现并修复的两个 bug

**① 挂起任务遇引擎会话缺失会永久卡死。** 恢复分支条件不成立后落到新 attempt 分支，
而 `beginAttempt` 的 WHERE 排除了 `SUSPENDED` → 返回 -1 → 抛异常 → **此后该任务每条消息都抛错**。
修法：`beginAttempt` 放开 `SUSPENDED`（并发安全不受影响——并发抢占时先到者已把状态改成 RUNNING）。

**② `expire` 未覆盖 `RUNNING`。** 进程被杀（部署重启 / OOM）留下永久 RUNNING 行——`release` 只在异常路径执行，
崩溃时不走。且 `findActive` 见它会当成"有 attempt 在跑"。修法：TTL 条件加入 `RUNNING`。

### 10.4 删除

`business/session/` 整包（`AgentSessionServiceImpl` / `AgentSessionState` / `Entity` / `Mapper`）——
切到 Task 后无任何调用点，留着就是同一概念的两个映射。
⚠️ 这**同时移除了第 1 步在该包里的 `release` 修复**——它已被 §10.2-① 的架构变更取代（抢占下移后
该中间状态不存在），不是丢失。

`OpsRunner.engineSessionIdOf`（`"ops-" + conversationId`）删除——留着只会被误用。

### 10.5 未做（有意）

| 项 | 理由 |
|---|---|
| **§四 的「新目标信号」** | 正是本文档自评的**致命风险**（判错则上下文断裂）。去掉它后行为**严格优于现状**（无场景更差），符合 HITL 那条「最坏退化 = 现状」原则。等 Task 层跑稳再加 |
| **存量数据迁移** | 库内为测试数据；跳过引擎侧改名（高风险、低收益）。旧 id `"ops-" + conversationId` 成为孤儿，`sa_agent_session` 表加废弃标注后保留 |
| **OpsRunner 的端到端测试** | ✅ **已补齐并真机验证**，见 §10.6 |
| Topic / Findings | 第 4、5 步 |

---

## 10.6 真机验证（2026-09-19，浏览器驱动）

新代码此前从未执行过（`sa_agent_task` 0 行、`ops_engine_session` 新格式 0 条），
以干净环境走完整链路。

**① 单测**：`OpsRunnerTaskTest`（复用 `OpsGraphSuspendTest` 引擎骨架 + 内存任务服务）
覆盖四轮调用：首轮建任务 → 补答 → 确认收尾 → 追问。首版**失败**——暴露 `markSuspended`
不得写 `attempt_count` 这一隐含不变量（测试替身照抄了旧值，把 `beginAttempt` 的递增冲掉）；
生产实现因"只更新 status/槽位"而侥幸正确，已在 javadoc 上写死该不变量。

**② Mapper SQL**：`claim`/`beginAttempt`/`release`/`expire` 全部在真实 PG 上以 `BEGIN…ROLLBACK` 验证：
并发第二刀抢到 0 行、`CONCLUDED` 追问序号 1→2、`release` 幂等返回 0 行、`expire` 如期改 `ABANDONED`。

**③ 浏览器端到端**（前端 :5173 + 后端 :9081 + 真实 PG）：

| 场景 | 观测 |
|---|---|
| A 新会话 | 建出 `sa_agent_task` 行（status=SUSPENDED, attempt_count=1）；引擎会话 id = `ops-<taskId>#1`（**新格式**） |
| 挂起恢复 | `attempt_count` **仍为 1**（恢复不递增）；stage `collect_slots → confirm_slots`；未产生 `#2` |
| 出结论 | status = **CONCLUDED**（非旧实现的 DONE）；结论为四段式 |
| **C 追问（不认可结论）** | **同一 `taskId`**（未新建）；attempt_count **1→2**；`#1` 存为 COMPLETED **未被覆盖**；`#2` 独立新建 |
| **槽位继承** | `#2` 的槽位快照：`interface`/`environment`/`trace_id` **与 `#1` 完全一致** |

最后一行是本次改造的靶心：用户说"结论不对"时，系统**不再从零重排**。
`#2` 直接推进到 `confirm_slots` 而未重新问齐，即为继承生效的行为证据。

---

## 10.7 第二轮真机测试（2026-09-19，Findings 链路 + 一个路由 bug）

### 验证通过的部分

新起会话走完整诊断后落库 **6 条主张**（1 ROOT_CAUSE 带 5 条证据 + 1 FIX + 4 RISK），
与 §12.1 从真实数据估的 3-6 条吻合。`FindingExtractor` 的纯解析在真机上成立。

### ⚠️ 发现并修复：CONCLUDED 任务的追问被 RAG 线劫持

**现象**：诊断出结论后，用户回"我不同意「避免重复红冲」这条风险……请据此修正结论"，
系统答成

> 当前可用信息中并没有「避免重复红冲」这条风险结论……

——**被 RAG 线接走了**（回复带 `[2](#cite-2)` 引用），任务停在 `CONCLUDED`、`attempt_count` 没变、
未开出 `#2`。诊断上下文一点没用上。

**根因：实现与设计文档不一致。** 设计（§3.2/§四）写着 `CONCLUDED` = *"已出结论，等用户反应"，
默认沿用*；而 `ResumeCoordinator.findResumable` 实现成了"只有 SUSPENDED 短路路由，
CONCLUDED 交给意图分类"。**把"等用户反应"的状态当普通新问题路由，与状态定义相悖。**

**修复**：`findResumable(conversationId, question)` 改为
`SUSPENDED → 归它` / `CONCLUDED → 默认归它`，并补上设计里说过的**新目标强信号例外**——
本轮消息里出现与任务已确认值不同的 **traceId** 或**接口路径**时交回意图分类（确定性判据，不耗模型）。

**守卫**：`ResumeCoordinatorTest` 11 例（异议必归诊断、换 traceId/接口算新目标、同接口仍归它、
任务关键数据缺失时不误判、空消息不误判…）。

### 修复后的真机复验（全部通过）

```
任务:      a1dd1d86-…  attempt_count = 2       ← 异议正确归入既有任务并开新 attempt
引擎会话:  #2 SUSPENDED   #1 COMPLETED          ← 旧会话未被覆盖
prior_findings 槽:                              ← 注入生效
  上一轮已确立的主张（用户可能正对其中某条提出异议，请据此回应或修正）：
  - [ROOT_CAUSE] order-service 在 prod 环境处理 POST /api/invoice/reverse…
  - [FIX] 校验通过，修正后报文如下…
```

确认后跑完 attempt #2，**主张的失效语义正确**：

```
SUPERSEDED | attempt_no=1 | 6 条   ← 旧主张转「被取代」，未删除
ACTIVE     | attempt_no=2 | 6 条   ← 新结论主张生效
```

闭环成立：**结论 → 抽主张落库 → 追问带上主张 → 新结论取代旧主张**。

### 教训

这条 bug **单测覆盖不到**——`OpsRunnerTaskTest` 直接调 `OpsRunner`，绕过了
`ResumeCoordinator → ChatOrchestrator` 这段路由。只有把消息从 UI 打进去才会暴露。
§10.5 里"端到端没有覆盖"那条自评，这次以另一种形式被印证了。

**通用教训：单测能验证"组件做对了它被要求做的事"，验证不了"组装起来是不是对的事"。**
路由层（谁决定把消息交给谁）尤其如此——它是各组件之间的胶水，没有单一归属。
