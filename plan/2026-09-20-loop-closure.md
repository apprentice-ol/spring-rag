# 追问循环闭合与增强（P0–P1.5）— 2026-09-20

## 一、背景与目标

`plan/2026-09-20-task-qa-loop.md` 五轮迭代后，追问循环剩五条边界（该文档 §6.5「诚实的边界」）。
本计划闭合其中四项，一项维持不做：

| 项 | 边界 | 决策 | 依据 |
|---|---|---|---|
| **P0** | DECIDE 卡片糊状文本 | **做**：结构化假设 | 真机轮 5 已踩到（adjust 用尽挂起，卡片与用户诉求脱节）；协议件 `HumanRequest.DecisionContext` 就位 |
| **P0.5** | 分类边界误判 | **做**：一词换轨 + 误判检测环 | 不追求完美分类，把"必须判对"降级为"判错可一词纠正、可发现回流" |
| **P1** | QA 轮无预算记账 | **做**：qa_count 配额 | 防单任务无限追问长尾；并发保护已有（degradeGate），缺累计计数 |
| **P1.5** | Topic 层暂缓 | **做最小形态**：同会话历史诊断注入 | 用户经验确认"跨任务互引"真实存在但低频——conversation 即天然关联，不建 Topic 表不建 related 边 |
| P2 | attempt 间无增量 | **不做**（本轮） | directive 注入刚上线，先观察其复读率影响再决定阶段级重入 |

---

## 二、P0：DECIDE 结构化假设

### 痛点（真机轮 5 原句）

> 阶段 查日志定位 自动重试额度已用尽
> 自动重试 1 次仍未得到可用结论：上一阶段无任何文本产出，说明日志查询未得到有效结果或未执行，需换条件重试

用户不知道该补什么。应升级为：

```
h1 知识库缺该接口的字段表     [已验证] 证据：检索未命中（通道有候选被精排过滤）
h2 检索问句改写得不好         [待验证]
判别动作：提供接口文档 / 样例报文，或回复「重查」
```

### 协议（agent-core，通用能力非业务侵入）

`HumanRequest.DecisionContext` 增加字段：

```java
record Hypothesis(String claim, String status, String evidence, String nextAction)
// status: verified（已证实）/ disproved（已排除）/ unverified（待验证）
record DecisionContext(String summary, List<String> evidence, List<Hypothesis> hypotheses)
```

- 紧凑构造器兜底 `null → List.of()`：**旧 JSON（无 hypotheses）反序列化兼容**，
  挂起中的存量会话恢复不受影响
- 序列化通道不变（`pending_human_request` 槽位 JSON）

### 判据（replan.md 输出扩展）

ask_human 裁决输出增 `hypotheses` 数组。判据三条（呼应"假设必须可证伪"的原始讨论）：

1. 每个假设**必须带 status 与 nextAction**——说不出"什么动作能验证/排除它"的假设**丢弃**
   （防无判别动作的空话假设进入卡片）
2. 已排除的假设也要列（status=disproved）——用户看到的决策面是完整的假设空间，不是残卷
3. 无假设可列时省略字段 → 卡片退化为现状文本（不劣化）

### 落点

| 文件 | 改动 |
|---|---|
| `agent-core definition/workflow/HumanRequest.java` | `Hypothesis` record + `DecisionContext` 加字段（null 兜底） |
| `prompts/workflow/ops_diagnose_v2/replan.md` | ask_human 判据 + hypotheses 输出格式与示例 |
| `ops/node/ReplanExecutor.java` | `Verdict` 扩展 hypotheses；`decideRequest()` 带入；解析失败/空 → 现状 |
| `frontend ChatView.vue / chat.ts` | DECIDE 卡片渲染假设块（claim + status 徽标 + 判别动作）；未知字段静默丢弃是前端既有约定，未升级前端时自动降级为纯文本 |
| 测试 `ReplanHandoffTest` | 假设解析 / 无 nextAction 丢弃 / 无假设降级 / 旧 JSON 兼容 |

---

## 三、P0.5：一词换轨 + 误判检测环（零代码 + 一条 SQL）

### 一词换轨

- `qa-answer.md` 末尾固定尾注：**「如需重新排查，回复『重查』，或直接指出哪条结论不对」**
- `qa-classify.md` 速判例补：「重查」→ rerun（行动指令）
- 效果：快环→慢环的切换门槛从"会描述问题"降为"一个词"；`#decision:` 协议之外的最轻换轨通道

### 误判检测（分类质量的结构性保证 = 可发现、可回流、可沉淀）

误判有固定指纹：**QA 直答轮的紧邻下一轮用户消息含否定/重查词**。检测 SQL（人工周跑，
捞出的句子补进 qa-classify.md 速判例，prompt 热更不发版）：

```sql
-- QA 误判候选：QA 轮（assistant 含换轨提示标记）之后紧跟否定/重查的用户消息
SET TIME ZONE 'Asia/Shanghai';   -- 库时区陷阱：不 SET 会反向误判
SELECT m2.conversation_id, m1.content AS qa_answer, m2.content AS next_user, m2.created_at
FROM sa_message m1
JOIN LATERAL (
    SELECT * FROM sa_message
    WHERE conversation_id = m1.conversation_id AND id > m1.id AND role = 'user'
    ORDER BY id LIMIT 1
) m2 ON true
WHERE m1.role = 'assistant'
  AND m1.content LIKE '%回复『重查』%'          -- 换轨尾注 = QA 轮天然标记
  AND (m2.content LIKE '%不对%' OR m2.content LIKE '%不是这%'
       OR m2.content LIKE '%重查%' OR m2.content LIKE '%错了%'
       OR m2.content LIKE '%重新%')
ORDER BY m2.created_at DESC LIMIT 20;
```

不建表不建接口——先把环跑起来，量大再自动化。

---

## 四、P1：QA 预算记账

- **表**：`sa_agent_task` 幂等加列 `qa_count INT NOT NULL DEFAULT 0`（init.sql ALTER IF NOT EXISTS）
- **配置**：`OpsProperties` 加 `qaMaxPerTask`（`ops.qa-max-per-task`，缺省 10）
- **计数点**：QA 直答**成功**后 `taskService.incrementQaCount(taskId)`（分类消耗不计数——
  判 rerun 的轮次走重跑，由 llm_calls 预算管）
- **超限行为**：`tryAnswer` 入口检查 `qa_count >= max` → 返回固定 DIRECT 文案
  （"本任务追问解读已达上限（N 次）。如结论仍有疑问，请汇总新信息说明哪里不对，
  我会重新排查；或开启新对话。"）——**不降级 rerun**（rerun 更贵，正中滥用），
  且此检查在分类调用**之前**，超限轮零 LLM 消耗
- Entity/Mapper/State 同步加字段；`OpsRunnerTaskTest` 内存服务补 override

---

## 五、P1.5：同会话历史诊断注入（Topic 层最小形态）

### 语义

新 Task（换接口/traceId 强信号开出）的诊断过程能看到同会话早前任务的结论概要——
"刚才那个问题是连接池耗尽，这个是不是也一样"里的"刚才那个"从此可见。
**不建 Topic 表、不建 related 边**：conversation 即天然关联（同会话按时间序），
真机数据已证实 Topic 会退化 1:1，此为投影的最小可用版。

### 查询与注入

- `AgentFindingServiceImpl.relatedOf(conversationId, excludeTaskId)`：
  其他任务的 **ACTIVE** 主张，kind ∈ {ROOT_CAUSE, FIX}，**每任务 ≤2 条、任务数 ≤2、
  单条截 200 字符**，按 update_time 倒序（有界是硬要求，与 FINDING_MAX 同哲学）
- `OpsRunner` 新 attempt 的 prefill 写 `related_findings` 槽（SUSPENDED 恢复不写——
  同 attempt 已注入过）；无历史 → 不写（空段不出现）
- `IntakeStageModule` 声明槽；`OpsPrompts.compose` 在 prior_findings 段后追加：

```
## 关联诊断背景（同会话早前任务的结论，仅供理解上下文——不是本轮证据，
   本轮结论必须有自己的证据链）
- [早前任务·根因] order-service 连接池耗尽导致下单超时…
- [早前任务·修正] 报文补齐 invoiceCode 后校验通过…
```

### 防锚定（跨任务根因对模型是强锚："上次是 X，这次大概也是"）

双保险：① 段头降权标注（如上）；② verify.md 证据链铁律（"只写日志/知识库真实给到的内容"）
不变——关联背景不得出现在本轮证据链里。渲染行**不带 `[#n]` 编号**（编号语义是
"本任务主张可被主张变更精确指认"，跨任务主张混入会把否定标错对象）。

### 测试

注入内容正确 / 排除当前任务 / 有界（任务数与条数） / 单任务无历史时不出现空段 /
compose 渲染默认值。

---

## 六、执行顺序与依赖

```
P0（协议+prompt+执行器+前端）──→ P0.5（prompt 尾注 + SQL，随 P0 的 prompt 批次一起改）
        └──→ P1（表列+配置+计数）──→ P1.5（查询+槽+compose 段）
```

每项独立可验证、可单独回滚；P0 的前端渲染可后置（协议降级安全，先跑后端+现有文本卡片）。

## 七、真机验收场景

1. 复刻真机轮 5（诊断结论 →「从接口文档中看下是否符合规格」→ 检索未命中）后的
   DECIDE 卡片：应出现结构化假设（含"知识库缺文档[已验证]"）与判别动作；
2. QA 直答末尾出现换轨提示；回复「重查」→ 立即进入完整重跑（不重新分类纠结）；
3. 同一任务连续 QA 追问 11 次 → 第 11 次收到限额文案，attempt/主张零变化；
4. 同会话先诊断接口 A 出结论 → 换接口 B（强信号）新诊断 → 阶段 prompt 可见
   「关联诊断背景」段；B 的结论证据链中**不出现** A 的主张；
5. 全量单测绿（P0 的 ReplanHandoffTest 扩展 + P1.5 注入测试 + 既有 129 例无回归）。

## 八、不做什么（本轮边界）

- **不建 Topic 表 / related 边**（P1.5 即投影最小形态；升级条件：关联背景被频繁引用再评估）
- **不做阶段级重入**（P2：等 directive 注入的真机复读率数据）
- **不做 ActExecutor 环内 ask_user 的假设结构化**（环内追问多为信息补齐 CLARIFY，
  DECIDE 化的卡点在 replan；环内等真实 badcase）
- **不做换轨结构化按钮**（先验证一词换轨的触发频率；前端 DECIDE 卡片随 P0 顺带，
  DIRECT 尾随按钮另立项）

---

## 九、落地记录（2026-09-20）

四项全部落地，单测全绿，装配真机验证通过。

### 9.1 改动清单

| 项 | 文件 | 内容 |
|---|---|---|
| P0 | `agent-core HumanRequest` | `Hypothesis`（claim/status/evidence/nextAction + `actionable()`）+ `DecisionContext.hypotheses`（空兜底 + 两参兼容构造） |
| P0 | `prompts/.../replan.md` | 输出格式扩 hypotheses + 「假设外化」三条判据（必须带判别动作 / 已排除的也要列 / 无假设省略） |
| P0 | `ops/node/ReplanExecutor` | `hypothesesOf()` 解析（无 `next_action` 丢弃 + 日志）+ `Verdict` 扩字段 + `decideRequest()` 三处调用点带上 |
| P0 | `shared ClarifyRequest` | `ClarifyHypothesis` 镜像 + 字段追加（delivery 契约不依赖 agent-core） |
| P0 | `AgentBranchDispatcher` | `toHypotheses()` 投影 |
| P0 | `frontend chat.ts / ChatView.vue` | `ClarifyHypothesis` 接口 + 假设块渲染（h1/h2 编号 + 状态徽标 + 判别动作行）+ `hypStatusLabel()` + 样式（success/danger/signal 三色对应 verified/disproved/unverified） |
| P0.5 | `qa-answer.md` / `qa-classify.md` | 固定换轨尾注「回复『重查』」+ 速判例补「重查 → rerun」 |
| P1 | `init.sql` / `AgentTaskEntity` / `AgentTaskMapper` | `qa_count` 列（幂等 DDL）+ `incrementQaCount`（原子自增） |
| P1 | `AgentTaskServiceImpl` | `qaCountOf`（查询失败按未用，配额偏松不是故障）+ `incrementQaCount`（失败 warn） |
| P1 | `OpsProperties` | `qaMaxPerTask`（`ops.qa-max-per-task`，缺省 10） |
| P1 | `OpsRunner` | CONCLUDED 分支的配额门（**分类之前**：超限轮零 LLM 消耗；回固定文案不降级重跑）+ 成功后计数 |
| P1.5 | `AgentFindingServiceImpl` | `relatedOf(conversationId, excludeTaskId)`（ACTIVE + ROOT_CAUSE/FIX、≤2 任务 × ≤2 条、任务按最近优先） |
| P1.5 | `OpsRunner` | `RELATED_FINDINGS_SLOT` + `renderRelated()`（**不带 [#n] 编号**，单条截 200） |
| P1.5 | `IntakeStageModule` / `OpsPrompts` | 槽位声明 + compose 追加「关联诊断背景」段（段头降权标注） |

### 9.2 测试

- `ReplanHandoffTest` 12→14 例：假设外化（真机轮 5 夹具：verified+unverified+无判别动作丢弃）、
  旧 JSON 无 hypotheses 字段反序列化兼容；
- `OpsRunnerTaskTest` 2→4 例：QA 超配额回固定文案（断言零模型调用 + 不开新 attempt）、
  关联背景渲染（不带编号 / 单条截断 / 空列表空串）、重跑轮携带关联背景断言；
- 全量：`platform-business` **133 例**、`platform-agent-core` 164 例、
  `platform-shared/delivery/console` 全绿。
- 前端 `vue-tsc --noEmit` 通过。

### 9.3 装配真机验证（2026-09-20 11:25）

在 9082 端口起临时实例（不打断用户 9081 的服务），三项确认：

1. **Spring 装配通过**：`Started PlatformApplication in 18.553 seconds`——
   `OpsRunner` 新增的 `OpsProperties` 构造依赖注入正常（记忆约束「改完装配必须真启动」）；
2. **幂等 DDL 生效**：`sa_agent_task.qa_count integer DEFAULT 0` 已在库中；
3. **prompt 运行时走 classpath**：`sa_prompt_binding` **0 行**（无绑定包）→
   `PromptStorePromptProvider` 的「绑定包覆盖优先，未命中回退 classpath」走到回退分支 →
   本次三份 prompt 改动（replan / qa-classify / qa-answer）**运行时生效**。
   启动日志的「代码与线上差异 3 个」只是 `sa_prompt` 表里的展示副本过时，不影响解析
   （见记忆 `springai-rag-prompt-binding-inactive`）——若日后接入绑定包，需先
   `/prompt/{key}/sync-from-code` 收编，否则线上旧版会盖掉代码版。

### 9.4 浏览器端到端验证（2026-09-20，web-test）

在用户重启后的实例（9081）上用容器浏览器跑真机流程：

| 用例 | 结果 | 证据 |
|---|---|---|
| QA 直答 + **换轨尾注** | ✅ | 解释类追问的回答末尾出现「如需重新排查，回复『重查』，或直接指出哪条结论不对」，并引用 `[#1]` 主张 |
| **一词换轨** | ✅ | 回复「重查」→ `attempt_count` 2→3（走重跑，未被当普通追问） |
| **DECIDE 结构化假设** | ✅ | 卡片渲染 h1/h2/h3，各带状态徽标（待验证）+ 依据 + **判别动作**；对照真机轮 5 的糊状文本（「自动重试 1 次仍未得到可用结论，需换条件重试」）是实质改善 |
| 重跑免确认卡（P2） | ✅ | 重跑轮直达 replan 挂起，全程无「确认，开始排查」卡片 |
| qa_count 落库（P1） | ✅ | `sa_agent_task.qa_count = 1`（旧代码产生的任务为 0） |
| 配额超限文案 | ⏸ | 需 11 次连续追问，未跑；单测已覆盖且计数写入路径上面已验证 |

### 9.5 真机发现两个问题（一修一记）

#### 发现 A（已修）：换目标后槽位不更新

**现象**：用户改说「另一个接口 /api/order/create 也报错了」后，任务槽位仍是
`interface=/api/invoice/reverse` + 旧 traceId；而模型问的是新接口——说明它同时收到矛盾的
`{{slots.interface}}`（旧）与 `{{slots.user_directive}}`（新）。槽位是权威事实源却与当前意图不符。

**根因**：抽槽语义是「已确认值优先、只填空缺」（`SlotExtractExecutor`），而 `OpsRunner.execute`
无条件把 `task.slots()` 灌进 prefill——同一 Task 换目标时，旧值成了"已确认"，新值永远抽不进来。

**修复**：
- `OpsSlotCatalog.STALE_ON_TARGET_SHIFT`（`interface/trace_id/error/payload/response`
  = "对哪个目标的哪次故障"）+ `targetShifted()` 判定（确定性，不耗模型）；
  `environment/time/symptoms` 是"在什么条件下查"，保留。
- `OpsRunner.execute` 在 prefill 构造处应用清理——清空后抽槽才能把新接口抽进来。
- `ResumeCoordinator.looksLikeNewTarget` 改为委托同一判据，消除两处硬编码的漂移风险
  （避免"路由说是新目标、槽位却没清"）。

**测试**：`换目标_旧目标痕迹被清除_条件槽保留`（断言新接口**成功抽入** + 旧 trace_id/payload 清空 +
environment/time 保留）、`目标切换判据_同接口与补槽不触发`（4 反例 + 2 正例）。全量 **135 例绿**。

#### 发现 B（记录，待定方向）：P1.5 触发面比设计预期窄

**现象**：换接口消息**没有新建 Task**（同一 taskId，attempt 2→3）。

**链路**：`ResumeCoordinator` 对强信号只做到「交回意图分类」；路由回 ops 后
`OpsRunner.ensureTask → findActive` 仍会复用 CONCLUDED 任务。新 Task 只在 `findActive` 返回空时
创建，而它覆盖 OPEN/RUNNING/SUSPENDED/CONCLUDED——**只有 TTL 过期（ABANDONED）后才可能**。

**影响评估**：P1.5 的注入在正常对话里几乎不可达；但也没预想的紧迫——同 Task 跨 attempt 时
`prior_findings` 已把旧结论带上了，「刚才那个」在单 Task 场景本就可见。P1.5 的真正增量场景是
**「TTL 过期后的对话延续」与跨 agent 任务**，而非"同一对话里连续诊断两个接口"。

**两个方向（待定）**：
1. 让「新目标强信号」真正新建 Task（符合 task-layer 设计 §四原意；需把判定结果从
   `ResumeCoordinator` 传到 `OpsRunner`，涉及路由层改动）；
2. 接受窄触发面，把 P1.5 定位为「TTL 后的对话延续」能力，不额外投入。

### 9.6 遗留

- **配额超限文案**未真机跑（需 11 次连续追问）；单测覆盖 + 计数写入已真机验证。
- **P1.5 触发面**待定方向（§9.5 发现 B）：新建 Task 还是接受窄面。
- P2（阶段级重入）仍待 directive 注入后的复读率数据。
- 真机测试在测试会话留下痕迹：该会话停在 `SUSPENDED`（ver_act 挂起等回答），
  `qa_count=1`、`attempt_count=3`。
