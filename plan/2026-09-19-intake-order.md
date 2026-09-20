# 诊断入口顺序重构（2026-09-19，用户口径）

> 状态：**已实施**（2026-09-19）。本文档是这条流程的权威口径，实施前先读它。
> 关联：`plan/2026-09-19-human-in-the-loop.md`（人在环中 P1–P3 已落地）。

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
