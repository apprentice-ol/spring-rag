# 任务内直答路径（Task QA）+ 重跑降摩擦 — 2026-09-20

## 一、这一步要解决什么

Task 层（2026-09-19）把「结论 → 主张落库 → 追问带上主张 → 新结论取代旧主张」的**记忆循环**闭环了，
但用户对结论的反应仍然只有一条消费路径：**开新 attempt 重跑整个 SOP**。于是：

> 结论交付后用户问「为什么你会说是缺 invoiceNumber？」——
> 系统不回答这个问题，而是重新走一遍 抽槽 → 自主补全 → **确认单（再点一次「确认，开始排查」）** →
> 查日志 → 改报文 → 校验，最后又交出一份四段式完整诊断结论。

三类问题：

1. **解释类追问得到的是重新诊断**——贵（每轮多 3-5 次 LLM + 工具调用）、慢、不针对问题
   （四段式格式逼着它重新输出排查结论/修正动作/风险提醒）；
2. **每轮重跑强制过确认门**（`ConfirmExecutor` 无条件挂起出确认单）——10.7 节真机复验里
   「**确认后**跑完 attempt #2」就是证据；
3. 对应文章口径（plan 讨论 2026-09-20）：**「Loop 与上下文系统正交」只落了一半**——
   记忆投影（findings/summary）建了，但「读投影、不进循环」的消费路径没建。

## 二、方案：把「对结论的反应」分成三条消费路径

| 追问类型 | 例子 | 消费路径 | 状态 |
|---|---|---|---|
| 异议 / 纠正 / 补充 / 行动指令 | 「不对，其实是…」「再查一次」 | 新 attempt 重跑（带主张+槽位） | 已有，真机验证 |
| **对结论的提问 / 纯应答** | 「为什么」「展开第 2 条」「谢谢」 | **任务内直答：读投影，单次 LLM，不进 SOP** | **本步新增** |
| 新目标 | 换 traceId / 接口 | 交回意图分类（`ResumeCoordinator` 强信号） | 已有 |

### 2.1 追问分类（语义判断全交模型，2026-09-20 第三轮定稿）

初版是「确定性词表优先、LLM 兜底」，真机首日即踩坑（词「修复」命中「给我一个修复**后**的
报文」被判重跑）——词表在开放语言空间里覆盖度不够，踩坑加词是打地鼠。定稿反转为主从：

```
1. 协议文本（#decision: / #override:）→ RERUN（机器协议不是自然语言，确定性判定，不耗模型）
2. 其余 → 单次 LLM 分类（qa-classify.md：判据 + 速判例 + 结论摘要随行）
        → 模型不可用 / 输出不可解析 / 应答失败 → RERUN（降级不劣化）
```

- **判据的核心问题**（§6.4 三态化后）：「用户要的东西在**结论里、文档里、还是日志里**？」——
  结论里（理解确认 / 索取已有产物 / 假设推理 / 致谢应承）= answer；文档里（查接口文档 /
  字段规格 / 手册 / 术语，一次检索可答）= **retrieve（分类时同步改写检索问句）**；
  日志里（实时数据 / 新事实 / 行动指令 / 新目标）= rerun。
- **分类必须带结论摘要**（截 800 字）：判「用户要的东西是否已在结论里」（如修复报文在
  「修正动作」段）需要看见结论，只看消息原文判不准索取句。
- **偏置方向不变**：误判到 rerun 代价小（多几次调用，结论仍对），误判到 answer 才是事故
  （该重查的只凭旧结论答）——判据写明「拿不准选 rerun」，降级默认也是 rerun；
  分错不是终态，用户下一句「不对/重查」自然落回重跑。
- **判据沉淀在 prompt 资产而非代码**：改判据 = 改 md（可热更），不是改 Java 发版。
  真机踩坑句进速判例 + 单测钉子（判据被删测试立刻红）。

### 2.2 任务内直答（`TaskFollowUpQa.answer`）

- **位置**：`OpsRunner.run` 入口，Task 状态 = CONCLUDED 时先行判定；命中即短路返回，
  **不进 workflow 图**。
- **上下文组装（零新持久化）**：
  - 结论全文 ← `task.summary`（`markConcluded` 存的就是全文）；
  - 生效主张 ← `findingService.activeOf(taskId)`（编号渲染，与注入诊断的 `renderFindings` 同源同序）；
  - 槽位投影 ← `task.slots`；
  - 问题原文。超长截断（结论 6000 字符 / 主张沿用 FINDING_MAX=10、单条 400），截断显式标注。
- **执行**：单次 `SingleTurnModel.ask`（与 replan 裁决/回复解释器同源同横切），qa-answer.md 约束：
  只依据给定材料回答、材料没有的明说、**不输出新报文/修正动作**（那是重排查的产物）、
  发现用户其实在质疑时指路「直接说哪条不对，系统会重新排查」。
- **出口**：`OpsAnswer(kind=DIRECT, text, trace=null)`，走现有 DIRECT 交付（分段流式 + 落库），
  消息照常进会话历史。

### 2.3 不变量（QA 轮不做的事）

| 不做 | 理由 |
|---|---|
| 不递增 `attempt_count`、不 `beginAttempt` | 没有执行，只有解读 |
| 不 `markConcluded`（Task 原地停在 CONCLUDED） | 结论没变 |
| **不重抽 findings** | 解读不产生新主张；下一轮异议仍以原结论主张为靶 |
| 不抢占用（claim） | 无引擎执行，与并发 attempt 天然无冲突 |
| 不建引擎会话 | QA 轮零 `ops_engine_session/slots` 写入（顺带缓解存储大户） |

失败降级：分类后的应答调用失败（模型异常/正文缺失）→ 返回 empty → OpsRunner 落回重跑路径，
**用户永远不会因为 QA 路径拿不到回复**。

### 2.4 P2：重跑轮跳过重复确认门

- 新槽 `intake_confirmed`（NUMBER）：`OpsRunner` 在**新 attempt 且 `task.attemptCount() ≥ 1`**
  （即本任务是第 2+ 个 attempt）时预填 1；挂起恢复路径不填（恢复继续原 attempt 的流程）。
- `auto_gate` / `slots_regate` 分支最前面加一条：
  `slots.intake_confirmed > 0 && slots.missing_count == 0` → 直进 `inv_think`（表达式引擎已支持 `&&`，
  分支按声明顺序首中即出）。
- 语义：确认门的本意是「摊开被推断的前提让用户把关」——**前提没变（同一 Task 沿用槽位）就不该再问**；
  追问轮改的槽值来自用户自己的话或 `#override:`，天然经过用户。
- 已知取舍：首个 attempt 在确认门前被取消（极少）→ 下一轮会跳过确认。最坏退化 =
  一次没有确认卡的诊断，结论尾部的 auto_note 透明段仍在，可接受。

## 三、改动清单

| 文件 | 改动 |
|---|---|
| `ops/TaskFollowUpQa.java` | 新增：分类器 + 直答组装（plain class，配置装配） |
| `ops/OpsRunner.java` | 注入 QA 组件；CONCLUDED 短路判定；重跑轮预填 `intake_confirmed` |
| `ops/workflow/stages/IntakeStageModule.java` | 声明 `intake_confirmed` 槽；两处 gate 加跳过分支 + `auto_gate → inv_think` 边 |
| `engine/AgentEngineConfiguration.java` | `@Bean taskFollowUpQa`（复用共享网关 adapter + promptBody） |
| `prompts/workflow/ops_diagnose_v2/qa-classify.md` | 新增：LLM 兜底分类（一行 JSON 出） |
| `prompts/workflow/ops_diagnose_v2/qa-answer.md` | 新增：结论解读约束 |

不动：内核、workflow 图的既有节点/边（只加分支）、表结构、交付层、前端。

## 四、验证场景

1. **解释类追问**（CONCLUDED 后「为什么说缺 invoiceNumber？」）：
   - 不新建 attempt（`attempt_count` 不变）、Task 仍 CONCLUDED、findings 不变；
   - 回答为单次 LLM 产物，含结论依据，非四段式；
2. **异议仍走重跑**（「不对，报文里 invoiceCode 应该是…」→ 句首否定）：开 attempt N+1，行为与现状一致
   （`OpsRunnerTaskTest` 既有断言不回归）；
3. **重跑轮无确认卡**：attempt 2 直接从 `inv_think` 开始（不再挂起在 `confirm_slots`）；
4. **首轮仍有确认卡**（`intake_confirmed` 不预填）；
5. **模型不可用**：全部落回重跑路径（分类器确定性规则照常，LLM 步骤退化）。

## 五、不做什么（边界）

- ~~**不做** trace 事件~~ **已推翻（§6.3）**：QA 轮现带两步轨迹（task_qa_classify / task_qa_answer），
  workflowId 标记 `task_qa`；promptHash 仍留空（OPS 指纹盖不住 QA 资产，宁缺勿假）；
- **不做** QA 轮的预算记账：单次调用与意图分类同量级，不进 `llm_calls`（那是引擎 attempt 预算）；
- **不做** 假设结构化（DECIDE 卡片升级）/ D12 引用核算：上一轮讨论的 P3，与本步正交，后置；
- **不动** Topic 层（仍暂缓，见 context-architecture 计划）。

## 六、落地记录（2026-09-20）

- 新增：`ops/TaskFollowUpQa.java`（追问分类 + 直答组装，plain class 经 `AgentEngineConfiguration`
  的 `@Bean taskFollowUpQa` 装配）；prompt 资产 `qa-classify.md` / `qa-answer.md`；
  `TaskFollowUpQaTest`（8 例：解释直答 / 句首否定 / 行动优先于疑问词 / 协议文本 / 纯应答 /
  非 CONCLUDED 与空结论不截胡 / 模型缺席与异常落回重跑 / LLM 兜底三态）。
- `OpsRunner`：CONCLUDED 短路判定（findings 惰性供给，重跑轮不多付查询）；
  重跑轮（`attemptNo >= 2`）预填 `intake_confirmed=1`。
- `IntakeStageModule`：`intake_confirmed` 槽 + `auto_gate`/`slots_regate` 跳过分支 + `auto_gate → inv_think` 直通边。
- `OpsRunnerTaskTest` 补端到端：解释类追问走直答（不开 #2 attempt、主张不变）+
  异议重跑跳过确认门（以 DIRECT 收尾而非 CLARIFY 挂起）；既有异议场景回归通过。
- **实现期踩坑**：跳过分支最初写 `slots.intake_confirmed > 0`——槽位未设置时值为 null，
  表达式引擎的数值比较退化成字符串比较（`"null" > "0"` 为真），全新会话被误判成已确认、
  绕过确认门直跑三阶段（`OpsGraphSuspendTest` 两例当场抓出）。改为 `== 1`
  （`equalsValue` 对 null 安全返回 false）。
  **通用教训：对可空数值槽位做分支判定，用 `== 字面值`，不用 `> 0`。**
- 验证：`mvn -pl platform-business test` 全量 **128 例通过**。真机冒烟（起服务跑一轮
  诊断 → 「为什么」追问看直答 → 「不对」重跑看免确认卡）留待下次联调。

### 6.1 第二轮真机（2026-09-20）与修正

真机走查：诊断 → 确认 → 四段式结论 → 追问「**给我一个修复后的报文**」→
**复读了一份完整四段式结论**（P2 免确认卡生效，但 P1 被短路）。

**根因**：行动信号里宽召回的「修复」命中了「修复**后的**报文」——那是索取句不是行动指令，
用户要的报文就在上一轮结论「修正动作」段里。两处配合缺陷：

1. 分类器：行动信号只该收「重新执行排查」的动词短语。「修复」收紧为「修复一下 / 帮我修复」，
   「改成 / 换成」移除（假设句「…改成…行不行」交提问信号）；提问/索取信号补
   「给我 / 发我 / 再发 / 再贴 / 重复一遍」——索取词覆盖「要结论里已有的东西」。
2. qa-answer.md 规则 3 原文「不要输出报文」误伤索取请求：改为**原样引用材料里那份**，
   不修改不新造（结论没变，产物就不该变；要不同的产物 = 需要重新排查）。

`qa-classify.md` 判据同步补「索取结论里已有的产物 = answer」。
测试：`TaskFollowUpQaTest` 补 3 例（真机句回归钉 / 假设句含「改成」/ 收紧后的「帮我修复」），
15 例全绿。

### 6.2 第三轮修正（同日）：分类语义判断全交模型

信号词收紧是打地鼠——用户拍板「这个行为让大模型判断，罗列覆盖度不够」。改动：

- `TaskFollowUpQa` 删掉全部语义词表（句首否定 / 行动 / 提问 / 纯应答四张表），
  只留两条确定性规则：**协议文本**（机器协议不经模型）与**失败降级**（rerun 不劣化）。
- `qa-classify.md` 重写为判据 + 判别要点 + 六条速判例（含真机踩坑原句），分类调用带上
  结论摘要（800 字截断）——判索取句必须看见结论里有什么。
- 测试重心转移：单测不再假装能测模型的语义理解，钉三件事——裁决执行语义（answer→直答 /
  rerun·解析失败·模型缺席→重跑 / 协议不耗模型）、分类调用形态（摘要随行）、
  **判据钉子**（qa-classify.md 必须承载「索取已有产物」判别与真机原句速判例，用真实
  classpath 资产断言，判据被删测试立刻红）。7 例，全量 127 例绿。
- 顺带印证了记忆里的坑：`mvn -pl platform-business test` 不带 `-am` 会从 .m2 解析到
  **旧版 prompt 资产**，判据钉子当场红——这本身就是钉子在起作用（抓到陈旧构件）。

### 6.3 第四轮修正（同日）：QA 轮补轨迹

真机发现**追问轮轨迹丢失**——初版设计把「QA 轮 trace=null」写进了不做清单，理由是
"解读无工具调用无轨迹可看"。用户拍板推翻：QA 轮有两次 LLM 调用、有判据、有成本，
「这轮为什么这么答」恰是排查答非所问时最需要看的。修正：

- `TaskFollowUpQa` 构造 `TraceView`（paradigm=OPS、workflowId=`task_qa`、promptHash=null
  ——OPS 指纹盖不住 QA 资产，宁缺勿假），两步如实入轨迹：
  `task_qa_classify`（含判定 label 与理由 / 降级形态）+ `task_qa_answer`
  （入参摘要 = 材料规模：结论 N 字 / 主张 N 条 / 槽位 N 项）+ `incrementLlmCall` 各一。
- 判 rerun 时轨迹随 empty 一起丢弃——重跑路径有自己的完整轨迹，不会混轮。
- `AgentBranchDispatcher` 零改（`trace != null` 即发 trace 事件）；OO 深链本就可用
  （QA 的 sidecall span 同步挂在 chat/stream 请求 trace 下）。
- 测试：直答用例断言轨迹两步 / llmCallCount=2 / task_qa 标记；e2e 补轨迹断言。11 例绿。

### 6.4 第五轮修正（同日）：三态消费路径 + 重跑语义透传

真机第三轮暴露两个缺口：①「从接口文档中看下是否符合规格」被判 rerun 后**无目标重跑**——
investigate 查日志（本轮零新信息）查不出东西，挂在 adjust 重试用尽、evidence 与用户诉求脱节的
决策卡上；② rerun 虽有 `user_directive` 软通道（compose 已渲染），**OpsRunner 重跑时没写入**，
阶段 prompt 看不到追问句子本身（抽槽器以外无人消费它）。修正：

1. **分类三态化**（qa-classify.md）：answer（结论素材）/ **retrieve（一次知识库检索可答：
   查接口文档/字段规格/手册/术语，分类时同步改写检索问句，保留接口名/字段名/错误码实体词）**
   / rerun（需要日志等实时数据、新事实、行动指令）。判别主线升级为「要的东西在**结论里、
   文档里、还是日志里**」。
2. **retrieve 执行路径**（`TaskFollowUpQa.Retriever` 函数式通道，装配 = 与引擎同一
   `RetrievalTool` 同一参数）：改写问句 → 一次检索 → 命中（[ref=N]）进直答材料 → 回答。
   轨迹三步（classify → retrieve → answer），llmCallCount 仍 2（检索非 LLM）。
   通道缺席 / 缺问句 / 检索异常 → 一律降级 rerun（失败文本不冒充命中）。
3. **qa-answer.md 补检索规则**：优先依据「知识库检索命中」段回答并引 [ref=N]；未命中如实说明
   并指路用户提供文档——不用常识补文档内容。
4. **重跑语义透传**（OpsRunner）：attemptNo≥2 时 `prefill.put(USER_DIRECTIVE_SLOT, question)`——
   追问句子经「用户补充说明」段进各阶段 think prompt（软消费），rerun 不再失焦。

测试：retrieve 三例（改写问句断言 / 命中注入材料 / 三种降级）+ 真机句回归钉。全量 **129 例绿**。
预期：同一句「从接口文档中看下是否符合规格」→ 检索（改写问句）→ 命中则引 [ref=N] 回答，
未命中则明确说「知识库未检索到」并指路——**不再出现挂在日志查询上的脱节决策卡**。
