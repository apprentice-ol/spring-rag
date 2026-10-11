# 2026-10-11 bad case 归集与归结（Badcase Collection）

> 状态：**P1 已完成并真机验证**（2026-10-11；12 接口 + 前端全链路实测通过）；P2（评测低分勾选归集 + 轨迹面板挑）待做
> 关联：docs/requirements/api-eval.md（12 个新接口预期条目已登记并验证）

## 一、目标

把散落在各处的失败问答统一归集，规则化归结出失败环节，补完 ground truth 后一键转正为评测条目，
形成「发现 → 归集 → 归因 → 回归」闭环。三类价值：

1. 线上点踩的坏例不再丢失（此前全仓库无任何点赞/点踩能力）
2. 失败环节可统计（领域 × 环节分布 → 针对性优化：补文档 / 调分块 / 调 rerank 阈值）
3. 坏例回流评测集（`source=feedback`），防回归

## 二、核心模型：badcase 就是评测条目（统一，不建第二张表）

`sa_eval_item` 增 8 列（幂等 ALTER）：

| 列 | 类型 | 语义 |
|---|---|---|
| origin | VARCHAR(16) | 归集渠道：`online_dislike` / `eval_low` / `trace_pick` / `manual` |
| origin_ref | JSONB | `{messageId, conversationId, traceId, runId, evalItemId}` 回跳原始现场 |
| domain | VARCHAR(32) | 领域字典值（接口文档 / 技术手册 / 行政制度 / 自定义），字典可扩展 |
| badcase_status | VARCHAR(16) | `DRAFT` / `READY` / `IGNORED`；非 badcase 条目为 NULL |
| feedback_reason | VARCHAR(32) | 点踩原因：`answer_irrelevant` / `content_wrong` / `incomplete` / `not_found` / `other` |
| feedback_note | TEXT | 用户备注（可选） |
| evidence | JSONB | 归集时快照：召回明细（含分数/通道）、意图分类、citations、原始答案 |
| triage | JSONB | `{stage, auto, note}` 规则预判 + 人工确认 |

原有 `source` 语义不变（builtin / liverag / **feedback**），badcase 一律 `source=feedback`。

### 两区制

- **归集池**：首次归集自动建数据集「badcase 归集池」（无 collection_id，跨领域汇聚）。新 badcase 落这里，
  `DRAFT` + `enabled=0`，不参与跑批（`EvalRunner` 抽条目过滤 `badcase_status IS NULL OR = 'READY'`，双保险）
- **转正**：补完 ground truth 后搬进目标领域数据集（UPDATE `dataset_id`），置 `READY` + `enabled=1`，
  下轮跑批自动被抽中

## 三、归集流程

### 渠道（4 入口，分两期）

- **P1**：聊天页点踩（👎 + 原因下拉 + 备注）+ 管理页手工录入
- **P2**：评测 run 低分勾选归集（EvalRunDetail 行选择）、轨迹面板手工挑

### 处理链

```
用户点踩 → POST /eval/badcases {messageId, reason, note}
  → EvidenceExtractor 按 messageId 拉取：
       - sa_message（问题 = 上一条 user 消息 / 答案 / citations）
       - sa_agent_trace.steps（意图分类 slotWrites、召回明细 kb_chunks_data 等）
  → 组装 evidence JSONB + 规则归因（BadcaseTriageRules）算出 triage.stage
  → 幂等检查（同 messageId 已归集则更新 reason/note，不重复建）
  → 写入归集池（source=feedback, status=DRAFT, enabled=0）
```

domain 在点踩时不填（用户不知道），但归集时**把运行期的了解固化进 evidence**：
命中片段所属文档集合（`hitCollections`，doc_id 反查 sa_document → sa_doc_collection——react 线
chunk metadata 缺 collection_id 时也拿得到），管理页归因确认区以推荐 chips 呈现、一键填入；
`PUT /{id}/triage` 的 body 一并接收 domain——点踩答不出的领域在人工确认这一步闭环。

### 跨模块依赖说明

`platform-eval` 的 pom 已有 `platform-delivery` / `platform-business` 依赖（EvalRunner 复用
KnowledgeRunner 的既成惯例，"评测即真实"），故 EvidenceExtractor 直接使用 delivery 的 `MessageMapper`
与 business 的 `AgentTraceService`，**不新增 SPI**。

`slotWrites` 的 key 名（`kb_chunks_data` / `kb_chunks_all` / `react_tool_chunks`）是代码契约、随图演进，
**解析必须收口在 EvidenceExtractor 单点**，前端零解析。

## 四、归因规则（自动预判 → 人工确认）

| 证据条件 | stage |
|---|---|
| 无 trace（闲聊 / 缓存重放 / 降级） | `UNKNOWN` |
| intent ≠ 知识问答 或 needs_retrieval=false | `INTENT_MISROUTE` 意图误判 |
| 召回为空 | `NO_RECALL` 未命中 |
| 召回非空、citations 为空 | `FILTERED_OUT` 后处理砍光（rerank/融合阈值） |
| 期望文档召回但排名靠后 | `RANKING` 排序靠后 |
| 有 citations 但答案错 | `GENERATION` 生成问题 |

- 从评测 run 归集（P2）时 `sa_eval_metric` 自带 retrieved/expected，判定更准，且 ground truth 已在，可直接 READY
- 归因同步计算（纯 JSONB 解析，毫秒级），不引异步

## 五、转正（完整 ground truth 辅助）

管理页详情抽屉 →「转正」：

1. 选目标领域数据集
2. **expected_doc_ids 辅助**：在目标集合范围内用问题重新检索 → 候选文档列表（docId/docName/预览/分数）→ 勾选
   （badcase 本身"没召回对"，候选必须重新检索，不能从 evidence 里拿）
3. **expected_answer 辅助**：LLM 基于勾选文档起草 → 人工修订
4. 提交 = `UPDATE dataset_id` + `READY` + `enabled=1`

## 六、接口清单（`BadcaseController`，platform-eval，`/eval/badcases`）

| 接口 | 分期 | 用途 |
|---|---|---|
| POST `/eval/badcases` | P1 | 归集（幂等 by messageId） |
| GET `/eval/badcases` | P1 | 列表（origin/domain/status/关键词/分页） |
| GET `/eval/badcases/{id}` | P1 | 详情（原始问答 + evidence + triage） |
| PUT `/eval/badcases/{id}/triage` | P1 | 归因确认/修改 |
| PUT `/eval/badcases/{id}/status` | P1 | 忽略 / 恢复 |
| POST `/eval/badcases/{id}/promote` | P1 | 转正 |
| POST `/eval/badcases/{id}/search-candidates` | P1 | 转正辅助：目标集合内重检索候选文档 |
| POST `/eval/badcases/{id}/draft-answer` | P1 | 转正辅助：LLM 起草标准答案（prompt 资产 `eval/badcase-draft-answer`） |
| GET `/eval/badcases/stats` | P1 | 分布统计（domain × stage） |
| GET `/eval/badcases/feedback-flags?messageIds=` | P1 | 历史消息已反馈标记（批量） |
| GET `/eval/badcases/domains` | P1 | 领域字典下发（`rag.eval.badcase.domains`，允许字典外自由值） |
| POST `/eval/badcases/collect-from-run` | P2 | 评测 run 低分批量归集 |

## 七、前端改动（P1）

1. `ChatView.vue` 气泡操作条加 👎 → Popover（原因 + 备注）→ 提交后变"已反馈"态；
   历史加载后批量查 feedback-flags 回填状态
2. 新页 `BadcasePanel.vue`（`#/admin/badcase` 独立一级菜单）：
   筛选卡 + 统计条（domain × stage）+ 表格（行选择）+ 详情抽屉（原始对话 / 证据 / 归因确认 / 转正）
   + 手工录入弹窗。骨架照 `EvalRunTab.vue` / `EvalDatasetTab.vue`
3. `App.vue` 菜单项 + 内容区分派

## 八、决策记录（含被否方案）

| 决策点 | 结论 | 被否方案与原因 |
|---|---|---|
| 存储 | `sa_eval_item` 增列（统一模型） | 独立 `sa_badcase` 表 —— 用户要求与评测"兼容成一个东西"；双表有同步问题 |
| 领域划分 | 条目级 domain，预置字典可扩展 | 从 doc_collection 推导（粒度不够）；自由文本（难聚合统计） |
| 归因 | 规则预判 + 人工确认 | 纯手工（慢、质量靠人）；LLM 归因（重且有成本）；不归因（丢核心价值） |
| 数据集归属 | 两区制（归集池 → 转正） | 一步到位（脏数据混入正式集）；不分区（按领域评测不便） |
| 点踩交互 | 点踩 + 原因 + 备注 | 只点踩（缺用户主观视角）；含点赞（正样本回流本轮不需要） |
| ground truth 辅助 | 完整辅助（重检索候选 + LLM 起草） | 中等（只搜文档）/ 最轻（纯手填）—— 标注效率低 |
| 跨域取证据 | 直接依赖 delivery/business（pom 已有） | 新增 shared SPI —— 与 EvalRunner 复用 KnowledgeRunner 的既成惯例不一致 |

## 九、实施注意点

- init.sql 新增列必须写幂等 ALTER（自迁移只补写成 ALTER 的列——历史教训）
- `EvalRunner` 抽条目过滤 DRAFT
- 新接口要补 `docs/requirements/api-eval.md` 预期条目
- 归因规则与证据解析分离：`EvidenceExtractor`（取数）↔ `BadcaseTriageRules`（判环节），便于单测
