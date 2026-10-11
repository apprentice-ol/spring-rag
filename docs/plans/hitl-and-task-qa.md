# 计划归档 · 人在环中与任务问答循环（2026-09-19 → 2026-09-20）

> 2026-10-11 文档整理：本文按时间顺序合并四份文档，原文完整保留，各部分以 `---` 分隔。
>
> - 2026-09-19 · 人在环中（Human-in-the-Loop）设计（P1–P3 落地，P4 待做）
> - 2026-09-19 · 诊断入口顺序重构（用户口径）
> - 2026-09-20 · 任务内直答路径（Task QA）+ 重跑降摩擦
> - 2026-09-20 · 追问循环闭合与增强（P0–P1.5）

---

<!-- 原文件：docs/plans/2026-09-19-human-in-the-loop.md -->

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

---

<!-- 原文件：docs/plans/2026-09-19-intake-order.md -->

# 诊断入口顺序重构（2026-09-19，用户口径）

> 状态：**已实施**（2026-09-19）。本文档是这条流程的权威口径，实施前先读它。
> 关联：本文「人在环中（Human-in-the-Loop）设计」（P1–P3 已落地）。

## 一、目标流程（用户原话）

> 通过用户信息获取关键字比如 trace_id 和时间范围 → 然后查日志 → 补全接口信息、请求参数、响应参数 →
> 让用户进行确认 → 然后诊断；如果中间有疑问追问用户，这个是模型发挥。

展开成两段式：

```
① 用户给：时间 + 关键字（一般就给这两样；有时直接给 trace_id）
② Agent：关键字 + 时间窗 → 模糊查日志 → 从日志里拿到 trace_id
③ Agent：trace_id → 精查 → 补全【接口 / 请求参数 / 响应参数 / 报错】
④ Agent：还缺的（日志里挖不到的，如 环境）→ 问用户（环内可反复追问，模型发挥）
⑤ 用户：确认单确认（每项带来源与依据，可逐项改）
⑥ Agent：RAG 检索 + 校验 → 诊断结论 → 给用户结果
```

## 二、现状与目标的差距（实打实的三处）

1. **反查的位置错了**：现在是"自主补全层的最后一层自救"，只在 `missing_count > 0` 时才进 `auto_resolve`；
   必填齐了直接进确认，**日志一眼都不看**。目标：**拿到基础信息（trace_id 或 关键字+时间）就无条件查一遍**。
2. **挖完不回头**：日志挖出的证据只补 3 个槽且补完即止，规则层/推断层不会再吃新证据跑一轮
   （目前只给"接口"打了一处特例补丁 `AutoResolveExecutor` 反查后重跑接口规则）。
3. **没有"响应参数"的落点**：业务目录是七槽 `environment/interface/time/error/payload/symptoms/trace_id`，
   日志里挖到"业务系统返回了什么"只能塞进 `error`。

## 三、实施清单

### 3.1 新增 response 槽（决定项：新增槽，不并进 error）

- [x] `OpsSlotCatalog`：加 `response`（响应报文/返回码，选填，`resolvable=true`（日志反查挖），`inferable=false`）
- [x] `OpsSlotCatalog.renderForExtraction` / `declareBusinessSlots` 自动带上（目录驱动，无需另改）
- [x] 阶段 prompt 插值：`investigate.md` / `resolve.md` / `verify.md` 加 `{{slots.response}}`
      （verify 已有的"已知补充"段最该带：校验结论要看返回了什么）
- [x] `AskMissingExecutor` 问句、确认单、`SlotQuestion` 投影走目录驱动 ✓ 无需改
- [x] 校验护栏 `OpsSchemaResolver` 不动（它校验的是**请求**报文）

### 3.2 日志挖掘补 response（`AutoResolveExecutor.extractFromLogLines`）

- [x] 与 payload 同款"准入标记"：日志行自己写明是**响应/返回/result/response** 才提取
      （复用 `PAYLOAD_MARKER` 的写法，另起 `RESPONSE_MARKER`）
- [x] 两段式查询本已存在（① 关键字模糊查 → 取 trace_id；② trace_id 精查），无需改

### 3.3 顺序重构（intake 图）

- [x] `IntakeStageModule`：**删掉 `slots_gate`**（它已无判断，只剩转发），抽槽后无条件进 `auto_resolve`
      （`auto_resolve` 内部各层本就只在"该槽缺失"时才动手，多跑一次是零成本）
- [x] `AutoResolveExecutor`：**层序调整为 规则 → 日志反查 → 规则复跑 → 推断**
      （吃新证据：接口路径/业务叫法从日志来，时间窗据日志时间修正），然后才判定缺口
      —— 取代现在只补"接口"的特例补丁
- [x] 顺序保障：问齐（`collect_slots`）只在"日志也挖不出来"之后发生；
      期望的节点链（已实现）：`extract_slots → auto_resolve(规则→日志反查→规则复跑→推断) → auto_gate ─[缺]→ collect_slots ⇄ slots_regate → confirm_slots → 诊断`

### 3.4 反查前提（已实现，保留）

- [x] 关键字模糊查要求**关键字 + 时间窗都有**；`trace_id` 精查不受时间窗限制（回溯 lookback-days）
- [x] 日志源：`ops.openobserve.stream`（默认平台自身日志流）——生产要指业务系统日志流
      ⚠️ `environment` 槽目前**不参与日志过滤**（按流分流 / 按字段过滤二选一，尚未接，见下一节）
- [x] **服务排除（2026-09-20 补）**：`ops.openobserve.exclude-services`（默认 `${spring.application.name}`）
      —— 默认流里业务日志与平台日志同流，靠 `service_name` 区分。**不排除会自我循环**：
      平台把发给 LLM 的 prompt 打进日志，而 prompt 模板自带「请求报文 / 业务系统响应」字样，
      正好命中反查的准入标记 → 把自家日志当业务报文抓回槽位 → 再进 prompt → 再被读回。
      实测踩到（意图分类器 JSON 同时污染 `payload` 与 `response` 两个槽）。两条查询路径都要挂谓词。
      **指向真实业务日志流时该项留空。**

### 3.5 验收（能跑到结论的案例）

```bash
bash scripts/seed-oo-log.sh "" "发票冲红失败" "invoice-service"   # 日志带 /api/invoice/reverse 与「请求报文：{...}」
```
页面发：`traceId <打印的> prod 环境 帮我看看为什么报错`
期望链：`extract_slots → auto_resolve（精查 → 补出 接口/请求参数/报错）→ confirm_slots → 用户确认 → inv → res → ver → conclude`

**业务键（orderNo）反查**（2026-09-20 补：脚本原先只覆盖 traceId 精查与中文关键字，
而真实排查里用户手上往往只有业务单号——这条链路有代码无数据）：

```bash
bash scripts/seed-oo-log.sh    # 第 4 个参数是 orderNo，不传则随机生成并打印
```
页面发：`orderNo <打印的> 报错了，最近10分钟`
期望链：`extract_slots（业务键落 trace_id 槽）→ auto_resolve：looksLikeTraceId=false →
searchByKeyword（业务键当关键字模糊查）→ 命中 → 提取 trace_id 落槽 → 后续同上`
判据依据：`AutoResolveExecutor.looksLikeTraceId` 要求纯 hex 且 16-64 位，`ORD` 前缀天然不命中；
关键字优先级是「业务键 > 接口名 > 错误摘要 > 现象」。
⚠️ 反查要求**关键字 + 时间窗都有**，测试消息里要带时间（如「最近10分钟」）；
body 进 OO 视图截 200 字，故脚本把 orderNo 写在正文第 ~35 字处（报错描述与报文首字段各一次）。

## 四、已知未接（不要误以为已实现）

| 项 | 现状 |
|---|---|
| `environment` 参与日志过滤 | ❌ 没接。槽只进 prompt 与结论，`query_logs` 无环境维度——要按流分流或按字段过滤，待选型 |
| 日志里挖不出的槽（如 environment/symptoms） | 只能问用户（设计如此） |

---

<!-- 原文件：docs/plans/2026-09-20-task-qa-loop.md -->

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

---

<!-- 原文件：docs/plans/2026-09-20-loop-closure.md -->

# 追问循环闭合与增强（P0–P1.5）— 2026-09-20

## 一、背景与目标

本文「任务内直答路径（Task QA）」五轮迭代后，追问循环剩五条边界（该部分 §6.5「诚实的边界」）。
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
