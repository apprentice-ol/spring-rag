# 人在环中（Human-in-the-Loop）设计 — 2026-09-19

> 状态：P1、P2、P3 已落地（同日续做）。P4 待做。本文档为跨会话交接的权威说明。

## 一、问题诊断

ops 诊断线的人被架构压扁成两个极端：

| 身份 | 代码事实 |
|---|---|
| **数据源**（填槽器） | 恢复输入只有 `user_clarify` + `Input.slots` 两条通道，自由回复一律被当"待抽槽文本"（`AskMissingExecutor`） |
| **垃圾桶**（接盘侠） | `ReplanExecutor` escalate → `escalate_node` 终态；`OpsRunner.run` 对非 CLARIFY 出口直接 `sessionService.complete()`——**会话关死，证据/已排除假设全部沉没** |

四个断点：
1. 人的输入无语义通道（假设/领域知识/方向指令被抽槽器过滤掉）
2. 槽单向（infer 推断无"可推翻"状态，只有 autoNote 事后透明）
3. escalate 是终态不是决策点——replan.md 第 9 行写的语义本就是"升级为向用户追问"，实现没兑现
4. 自主性是目录静态二元声明（`withAuto(default, inferable, resolvable)`），无会话级旋钮

## 二、统一心智模型

现有挂起/恢复（`NodeResult.suspended` → engine.resume 重入）已是通用原语，
`AskMissingExecutor` 问齐与 `ActExecutor.ask_user` 是同一件事的两次特例化。
四个机制（问信息/决策移交/计划审批/关键确认）全部统一为该原语的变体：

```
挂起 = 引擎向人发出 HumanRequest（结构化请求决议）
恢复 = 人交回 HumanResponse（结构化决议），引擎消费后继续
```

## 三、协议（core：com.agentframework.definition.workflow）

```java
public record HumanRequest(
    Kind kind,                  // CLARIFY | DECIDE | APPROVE | CONFIRM
    String prompt,              // 问句正文（兜底渲染）
    List<SlotAsk> slots,        // name/label/当前值/provenance/options
    DecisionContext context,    // DECIDE/APPROVE：证据/已排除/候选假设
    List<Choice> options,       // value/label/description 点选项
    boolean allowFreeText
);

public record HumanResponse(
    Map<String, String> slotFills,   // 填槽
    Map<String, String> overrides,   // 推翻（槽名→新值）
    String directive,                // 方向指令（软消费：进 prompt，LLM 消化）
    String decision,                 // 命中的 option.value
    Integer autonomyHint             // 顺带调档
);
```

设计要点：
- **各字段全可选、可并存**——抗分类误差：分类不确定时 slotFills 与 directive 同时产出，
  prompt 注入是软消费，不存在"分错类就丢信息"；最坏退化 = 现状（整句当 user_clarify）
- **指令是软的**：directive 不改图结构，经 replan/adjust 机制消化——保留骨架可追溯性。
  硬控制（强制跳阶段）留到有真实 badcase 再做（升级路径，暂不做）
- **挂起承载**：不改 NodeResult 结构；HumanRequest 由挂起执行器
  `withSlotWrite("pending_human_request", json)` 暂存（与 `pending_ask` 模式同构），
  恢复重入时配对消费。经 `RunResult.slots()` 冒泡到 OpsRunner

## 四、分层落点（骨架零业务约束）

```
core（agent-framework）     HumanRequest/HumanResponse 协议 + 前缀解析（#decision:）
business/ops                ReplanExecutor 四态消费、prompt 改造
business/orchestration      HumanRequest(core) → ClarifyRequest(shared) 转换
shared（clarify SPI）        ClarifyRequest 扩展：kind/options/evidence/allowFreeText
delivery/sse                emitClarify 重载带结构化请求；DECIDE 渲染选项
frontend                    chat.ts ClarifyEvent 扩展 + ChatView 选项点选
```

依赖合规：ClarifyRequest（shared）自持有结构化字段，**不引用** core 类型；
HumanRequest→ClarifyRequest 转换在 business（两边可见），单向无环。

## 五、P1 改动清单（决策移交 + 结构化 clarify）

### P1a core 协议件
- [x] `definition/workflow/HumanRequest.java`（嵌套 Kind/SlotAsk/Choice/DecisionContext）
- [x] `definition/workflow/HumanResponse.java`（含 P1 简化解析：`#decision:<value>` 前缀 → decision 字段，其余全文 → directive）
- NodeResult 不动（suspended + withSlotWrite 已够）

### P1b ReplanExecutor 四态 + escalate→DECIDE
- [x] `prompts/workflow/ops_diagnose_v2/replan.md`：加 `ask_human` 第四态
  （仅当证据矛盾/adjust 额度将尽/多假设无法裁决；输出 question/evidence 字段）
- [x] `ReplanExecutor`：
  - `ask_human` → `NodeResult.suspended` + 槽位写 `pending_human_request`（HumanRequest JSON）
  - `escalate`（非 D12 场景）→ 同样 DECIDE 挂起（终止从默认结局变成**选项之一**）
  - adjust 额度耗尽 → DECIDE 挂起（原为硬 escalate 终态）
  - 重入消费：`pending_human_request` 非空 + 恢复输入 → 解析 →
    `redirect`（decision/directive 写 replan_note → adjustTarget 重跑）/
    `terminate`（→ ESCALATE_NODE 终态）→ 清暂存
  - D12 保留：有工具证据的 escalate 仍降级 continue
- [x] `OpsRunner`：`OpsAnswer` 加 `humanRequest` 字段（从 `result.slots()` 反序列化）；
  DECIDE 挂起走 CLARIFY kind（RunOutcomeMapper: suspended→CLARIFY 天然覆盖）→
  `saveAwaitingUser` → 会话不再被 escalate 关死

### P1c 交付扩展 + 前端
- [x] `ClarifyRequest`（shared）扩展：`kind`/`options`/`evidence`/`allowFreeText`（可空兼容）
- [x] `DeliveryPort.emitClarify` 加重载（带 ClarifyRequest），默认方法兜底旧签名
- [x] `SseDeliveryPort` 覆写：DECIDE 时文案渲染证据摘要 + 选项列表
- [x] `AgentBranchDispatcher.handleClarify` 传结构化请求
- [x] 前端 `chat.ts`：ClarifyEvent 加 optional 字段（旧事件零影响）
- [x] `ChatView.vue`：clarify 卡片渲染——options 点选按钮（点击发送 `#decision:<value>`）+ evidence 折叠区

### P1 验证
- [x] ReplanExecutor 四态单测（ask_human 挂起/escalate→DECIDE/重入 redirect/terminate）
- [x] 存量 OpsGraphSuspendTest 不破
- [x] `mvn -am` 全量编译 + frontend `vue-tsc`

## 五之二、P2 改动清单（回复解释器 + 语义通道）

### P2a 分类器
- [x] `prompts/workflow/ops_diagnose_v2/human-response.md`：输出一行 JSON
  `{decision, directive, slots, overrides, autonomy_hint}`；规则含「不要分类删信息」（多字段可并存）
- [x] `business/ops/HumanResponseInterpreter`：三条防线——① `#decision:` 点选回传不耗模型；
  ② 模型不可用/正文缺失/解析失败 → 退化 `HumanResponse.parse`（最坏 = P1）；
  ③ 槽名白名单（目录 ∪ 本次提问问到的动态槽）+ **按当前值本地调和** slots/overrides 分桶误差
- [x] 选项越权防护：decision 只认本次请求声明过的 option value，模型自造的一律丢弃
- [x] 「模型判空」区分：短应承（< 8 字）→ 空决议；够长的文本 → 整句进 directive（不丢信息）
- [x] `AgentCatalog.OPS.workflowPromptKeys` 登记新 key（进指纹 + 进绑定校验 + 管理页可见）

### P2b resume 链路接入
- [x] `ReplanExecutor.resumeFromDecision`：解释器解析 → 三条出路
  （terminate 终态 / 带新信息 redirect / **空回复重挂**：点了"继续"却什么都没说时原请求再问一轮，
  避免空跑一整轮阶段）
- [x] `AskMissingExecutor`：解释器先跑（补缺 + **推翻 auto-resolve 推断值** + directive + 档位提示），
  **解释器抽到槽则跳过抽槽器**（同一模型同一目录，再抽一次是重复付费）；
  没抽到（模型不可用/判空）才回落抽槽器——该路径与 P1 逐字一致
- [x] `replan_note` 改「指针」写法：用户指令原文只出现在 `user_directive`，
  replan_note 只写"为什么重跑"（同一份内容出现两次会让模型误判权重）
- [x] 环内 `ask_user`（ActExecutor）**不接解释器**：回复原文直接进 scratchpad 由 think 消化，
  软通道天然存在；且 workflow/common 不持有模型（要接需把 SingleTurnModel 塞进共用层，不值）

### P2c directive 进阶段 think prompt
- [x] `OpsPrompts.compose` 增加「用户补充说明」段：`{{slots.user_directive|（无）}}`
  （用模板默认值渲染占位，避免空标题诱发模型脑补），位置在「修正要求」之前
- [x] `ActExecutor.USER_DIRECTIVE_SLOT` 常量收口（原先散落字面量）

### P2 验证
- [x] `ReplanHandoffTest` 12 例（新增：终止意图识别 / 越权 decision 丢弃 / 槽位落槽 / 白名单过滤 /
  空回复重挂 / 判空兜底）
- [x] `AskMissingHandoffTest` 5 例（推翻推断值 / 抽到槽跳过抽槽器 / 没抽到回落抽槽器 /
  指令与槽值并存 / 解释器缺席同 P1）
- [x] `OpsPromptsTest` 2 例（directive 渲染位：在场下发、缺席占位、不漏 `{{}}` 语法）
- [x] `OpsGraphSuspendTest` 端到端补 P2 断言（解释器产物真的落进会话槽位）

### P2 顺手修的存量问题
- [x] `OpsPrompts.compose` **文本块被 P1 改断**（`""");` 提前闭合导致 89–91 行成孤儿语句）——
  platform-business 整体编译不过，测试跑不起来。已修。

## 五之三、P3 改动清单（自主档位 + 槽位来源状态机）

### P3a 档位策略（后端核心）
- [x] `ops/AutonomyLevel`（L1 多问我 / L2 默认 / L3 少问我 + `SLOT` 槽位名 + `parse`/`fromHint`）
- [x] `ops/AutonomyPolicy`：**目录声明 ∩ 会话档位**，交集偏保守——档位只能收紧不能放宽
  （L1 关缺省值/LLM 推断/日志反查；L3 推断门槛 0.7→0.5、反查额度 2→3）
- [x] `AutoResolveExecutor` 三层各自过策略闸门；L3 的额度真的用得上：带时间窗关键字查无果时
  **去掉窗口重试一次**（L2 到此为止直接问用户）
- [x] 档位取值优先级：本轮请求参数 > 用户回复里顺带调的档（`autonomy_hint` 1/2/3）> 会话记录 > 缺省 L2；
  不认识的档位串一律回 L2（绝不因为不认识就放大自主性）

### P3b 档位落与会话
- [x] `sa_agent_session` 加 `autonomy_level`（init.sql 幂等 ALTER，沿用 prompt_releases 同款迁移写法）
- [x] `AgentSessionState`/`AgentSessionEntity` 加字段；`AgentSessionServiceImpl.autonomyOf`（与状态无关地读）
- [x] `OpsRunner.withAutonomy`：本轮槽位没带档位时按会话记录补；挂起落库时把档位写回会话
- [x] 前端旋钮：请求参数 `?autonomy=L1|L2|L3`（空 = 跟随会话）→ ChatController → ChatService →
  ChatOrchestrator → AgentBranchDispatcher → 槽位；REST `/diagnose/trace` 也收同名参数

### P3c 槽位 provenance 状态机
- [x] `ops/slot/SlotProvenance`：结构化来源登记（rule/default/llm/log_query/**user_override**），
  存储沿用 `inferred_slots` JSON 数组（method 即来源，向后兼容），`upsert` 同槽**替换**不清史
- [x] 用户推翻 → 来源转 `user_override`（判定用「改写前有没有值」，`#override:` 与自由文本同口径）
  实现在 `AskMissingExecutor.overriddenProvenance` / `ReplanExecutor` 同名方法
- [x] 协议新增确定性回传 `#override:<slot>=<value>`（`HumanResponse.parse` + `isProtocolText`）——
  点一下改一个槽位，**零模型调用**；格式不对按普通文本兜底（原文进 directive，绝不静默丢）
- [x] 交付投影：`SlotQuestion` 加 `value`/`provenance`；`ClarifyRequest` 加 `reviewed`
  （待补问题 / 已补待确认两组分开，卡片据此渲染角标与纠正入口）
- [x] 前端卡片：「已自动补全」行 = `槽位 = 值 + 来源角标 + 「改成 …」候选点选`；
  无结构化 `reviewed` 时退回 P1 的文本 evidence 块（旧事件零影响）

### P3 验证
- [x] `AutonomyPolicyTest` 4 例（解析容错 / L1 只收紧 / L2 保既有 / L3 不突破目录）
- [x] `AutoResolveAutonomyTest` 4 例（同输入三档结果不同 / 档位槽缺失回 L2）
- [x] `HumanResponseTest` 5 例（decision 与 override 协议解析 / 格式错兜底 / isProtocolText）
- [x] `AskMissingHandoffTest` 扩到 7 例（点选纠正零模型调用且改写来源 / 已补全项带角标透出给卡片）
- [x] 全量回归 54 例绿；`vue-tsc --noEmit` 通过

## 五之四、P3 后真机体验修复（2026-09-19，MCP Docker 浏览器实测）

**实测发现的两个后端缺陷（单测看不见，只有真跑才暴露）**：

- [x] **遥测 JSON 被当"请求报文"补进 payload 槽**：`AutoResolveExecutor` 的报文提取取"日志里最长的
  JSON 块"，而应用自身的 `{"_event":"step.output",...}` 恰好最长 → 卡片上摆出一坨机器 JSON，
  同时毒化第二阶段的报文生成输入。修：`TELEMETRY_JSON` 首键白名单过滤 + 长度上限 4000
  （回归测试 `AutoResolveLogPayloadTest`：日志里同时有遥测与真报文时，必须抓到真报文）
- [x] **override 后重问的卡片仍显示旧值**：`writeClarifyRequest` 读 `context.slots()`——那是**写入前**的
  快照，override 翻新的台账只在待写的 `writes` 里 → 用户点了「改成 最近1小时」，新卡片still 显示旧窗口。
  修：`effectiveProvenance(writes, context)` 写入优先 + 补全说明改由台账重建
  （回归测试：`重入_点选纠正后重问的卡片不再显示旧值`）

**交互与展示（用户反馈"基本无法使用 / 体验很差"的直接原因）**：

- [x] **点选即发送 → 点选填入输入框**：原来点一次候选值就发一条消息、每点一次要等一整轮诊断，
  「可一次答多项」根本做不到。现在点选 = 追加进输入框并聚焦（同值不重复追加），用户一轮答全再发
- [x] **决策卡片主按钮不再空发**：点「我补充信息，继续排查」原先发一条空消息 → 后端判"没收到新信息"
  原样再问一轮，按钮看起来就是坏的。现在改为聚焦输入框让用户说；只有「终止排查」是立即回传
- [x] 时间窗 `2026-09-19T17:38~2026-09-19T18:08` → `2026-09-19 17:38 ~ 18:08`；长值单行截断（title 挂全值）
- [x] 卡片收紧：模型自己组织的问法作引导句（套话不重复展示）、来源角标弱化、脚注一句话

**第二轮真机反馈（用户实测）与修复**：

- [x] **"报文"槽抓到我们自己的 prompt 模板**：日志反查点的是**平台自身的 OpenObserve 流**
  （`ops.openobserve.stream` 默认 `springai_rag_logs`），里面既有遥测 JSON、也有 replan 的输出协议模板
  （`{"action":"continue|adjust|ask_human|escalate",...}`）→ 被"最长 JSON 块"选中当真报文。
  修：① 整行是平台遥测 JSON 的直接跳过；② **报文只从自己写明"报文/入参/请求体/payload/request"的行里提取**
  （报文是高影响槽，宁可漏——漏了模型会在第二段用 ask_user 问用户）
- [x] **"已自动补全"只能看不能改**：原来只有带候选值的槽才有「改成 …」按钮，报文/关键数据这类
  一个按钮都没有，"不对就点改成 …"的提示等于骗人。修：**每行都给「改」（就地输入框）与「清空」**，
  协议侧支持 `#override:<slot>=`（空值 = 清空该槽）
- [x] **"这值哪来的"无处可查**：卡片只显示来源角标（缺省值/日志反查），没有依据。
  修：`SlotAsk`/`SlotQuestion` 加 `evidence`，卡片每行透出「日志反查关键字「X」命中」这类原文
- [x] 候选值补充说明的措辞与位置：脚注改成"点候选值会填进下方输入框（可一次答全多项），确认后再发送"

> 浏览器实测路径：新会话发一句"XX接口报错了" → 问齐卡片（编号问题 + 候选值 + 已自动补全行）→
> 点候选值（填入输入框）→ 点「改成 最近1小时」（确定性回传，气泡显示"时间 改为 最近1小时"）→ 续跑。

## 五之五、第三轮反馈修复（用户口径：反查要有前提 / 先确认再诊断 / 轨迹不断链）

- [x] **确认门 `confirm_slots`（新的挂起点，CONFIRM 协议首次实战）**：槽位齐备后**不直接开诊断**，
  先出确认单——把「我理解的这些信息」逐项摊开（值 + 来源 + 依据），用户点「改/清空」就地纠正，
  点「确认，开始排查」才进阶段 1。图上：`slots_gate/auto_gate/slots_regate` 的齐备出口统一改指
  `confirm_slots`（动态边回 `inv_think`）；非确认回复一律刷新确认单再问一轮（改完还能继续改，
  不会顺手把人推进诊断）。自由文本认「确认/没问题/可以…」是保守关键词，点按钮才是主路径
- [x] **反查前提：关键字 + 时间窗都要有**。原来只有关键字没时间窗也会连库全时段扫——慢且容易
  捞到无关记录（实测把平台自己的日志当业务日志：prompt 模板、意图分类 JSON 都被当"报文"）。
  日志源本就是 `ops.openobserve.stream`（默认 `springai_rag_logs` = 平台自身日志流），
  生产要指到业务系统日志流才有意义
- [x] **报文提取收紧**：整行是平台遥测 JSON 的直接跳过；报文**只从自己写明"报文/入参/请求体/payload/request"
  的行里提取**（高影响槽，宁可漏——漏了模型会在第二段 ask_user 问用户）
- [x] **诊断链不断链**：`sa_agent_session` 加 `chain_trace_id`——首轮挂起时写入当轮 traceId，
  追问轮沿用同一个（`ChatOrchestrator` 从 activeSession 取）。一次诊断从追问到结论是同一条链，
  OpenObserve 深链与轨迹回看不再散成 N 段孤立轨迹
- [x] 「已自动补全」行：每项都能就地改/清空（原来只有带候选值的才有按钮，提示等于骗人）；
  每行透出**依据**（`SlotAsk.evidence` / `SlotQuestion.evidence`），回答"这值哪来的"

### P1–P3 遗留的已知边界（留给 P4 / 后续）
- 环内 `ask_user`（ActExecutor）的回复仍只进 scratchpad：档位/provenance 都不参与该路径
- `inferred_slots` 的来源详情（confidence、命中关键字）只在文本 note 里，结构化条目只带来源键；
  卡片角标够用，要看细节去轨迹
- 档位旋钮是**请求级发送 + 会话级落库**：不改变历史会话已存的档，换档后本轮即生效并沿用到后续轮
- 档位选项目前前端硬编码（三档固定），未进 `/agent/registry` 能力清单；要动态化时补一处投影即可
- 无候选值的自动补全槽（如日志反查出的 interface）只给角标不给「改成 …」按钮，
  纠错走自由文本（P2 解释器识别为 overrides）

## 六、P4 概要

| Phase | 内容 | 依赖 |
|---|---|---|
| P4 | APPROVE 挂起点（L1 档位下阶段切换前挂计划审批）；ActExecutor 环内 escalate 统一 DECIDE | P3 |

## 七、风险与防线（用户已确认关注）

1. **挂起频率是体验死穴**：四态全开模型可能频繁 ask_human 逃逸责任。
   防线：replan prompt 明确"ask_human 仅当证据矛盾/额度将尽/多假设无法裁决"；
   L3 档下 DECIDE 照挂（模型能力边界）；CONFIRM/APPROVE 类受档位控制（P3/P4）。
2. **指令软消费 = 建议权非控制权**：刻意取舍（硬控制会让状态机复杂度爆炸且不可追溯）。
   若试点发现指令常被无视，升级路径 = directive 强制位（跳阶段直接 dynamic 边），留待真实 badcase。

## 八、验收口径（量化）

HumanRequest/Response 落 sa_agent_trace（轨迹域已有），看三个数：
- **移交后继续率**：DECIDE 挂起后经用户回复继续跑完的比例（原 escalate = 0%，会话关死）
- **人纠错采纳率**：override/directive 实际改变了后续阶段行为的比例
- **档位切换分布**（P3 后）：旋钮是否有人用

## 九、链路备忘（实施时确认过的事实）

- 挂起冒泡：`NodeResult.suspended` → `RunResult(state=SUSPENDED, suspendedNode, output, slots)`
  → `RunOutcomeMapper.kindOf`（suspended→CLARIFY）→ `OpsAnswer(kind, text, trace)`
  → `AgentBranchDispatcher.handleClarify` → `SseDeliveryPort.emitClarify` → SSE clarify 事件
- 结构化丢失点：`ClarifyRequest.questions` 恒为空 List（SseDeliveryPort:69 硬编码）——P1 已改
- **P2 解释器接线点**（分类在 executor 内，不在 OpsRunner）：每个执行器自己认识挂起暂存，
  `HumanResponseInterpreter` 由 `SharedDeps.humanResponseInterpreter()` 现造（纯函数式，无需共享实例）；
  `llm_calls` 由执行器按 `Outcome.llmCalled()` 计数，与 O9 口径一致
- **P2 新增槽位**：`user_directive`（软指令，进 think prompt）、`autonomy_hint`（档位提示，P3 消费）；
  二者都不是目录槽，`OpsRunner.clarifyStateOf` 的 confirmed 快照不含它们（只影响会话状态记录，不影响引擎槽位）
- 恢复：`OpsRunner.execute` load 引擎会话（id=`ops-<conversationId>`，SUSPENDED 态）→
  `engine.resume(Input(text, Map.of(), {user_clarify: text, ...sessionSlots}))` → 挂起节点重入
- `emitEscalate` 现为纯文本终态；OpsRunner 对 ESCALATE kind 调 `sessionService.complete()`
- ClarifyRequest 全库仅 SseDeliveryPort 一处构造（改构造签名安全）
- 前端：chat.ts clarify 分支须在 else 兜底之前（既有约束）；ChatView clarify 卡片只渲染 questions
