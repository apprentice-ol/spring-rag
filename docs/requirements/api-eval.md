# api-eval — 评测接口预期

### POST /eval/datasets — 创建评测数据集
- 状态: ✅ 已验证@2026-10-11-1809-local（重名 409 精确语义：message「name「冒烟测试」已存在，请换一个」；成功档 200+id；测试数据已自清理）
- 抽检: Y
- 前置: 重名场景需库中已存在「冒烟测试」数据集（现存脏数据即种子）；成功场景用随机名 `smoke-<时间戳>`，测后自清理
- 成功：2xx + 响应含 `id`（Long）
- 异常：① name 与已有数据集重名 → 409，message 含 `已存在，请换一个`；② name 为空/空白 → 400，message 含 `数据集名称不能为空`
- 兜底：未知错误 → 统一错误体 `{"code":500,"message":"服务器内部错误: …"}`（不裸堆栈）
- 来源/变更：`docs/plans/2026-10-11-devloop-health-process.md` §一.6、§五.4；改预期须用户确认

---

## bad case 归集（2026-10-11；设计 `docs/plans/2026-10-11-badcase-collection.md`）

### POST /eval/badcases — 归集（点踩入口，幂等 by messageId）
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: Y
- 前置: 存在一条 assistant 消息（其 `sa_message.id` 作为 messageId）；幂等场景 = 同 messageId 重复提交
- 成功：2xx + 响应含 `id` / `badcaseStatus=DRAFT` / `triage.stage`（6 类之一）；重复提交返回同一条目 id（原因/备注更新，不重复建）
- 异常：① messageId 缺失/为空 → 400，message 含 `messageId 不能为空`；② messageId 不存在 → 404，message 含 `消息不存在`
- 兜底：未知错误 → 统一错误体（不裸堆栈）
- 来源/变更：2026-10-11 badcase-collection；改预期须用户确认

### POST /eval/badcases/manual — 手工录入 bad case
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 无
- 成功：2xx + 响应含 `id` / `origin=manual` / `triage.stage=UNKNOWN`（无现场证据）
- 异常：question 为空/空白 → 400，message 含 `question 不能为空`
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### GET /eval/badcases — 列表（来源/领域/状态/关键词 + 分页）
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: Y
- 前置: 归集池至少 1 条（可先用 POST 归集造数）
- 成功：2xx + `{total, records:[BadcaseView]}`；records 仅含 `source=feedback` 条目；origin/domain/status/keyword 过滤生效；分页 `LIMIT/OFFSET` 正确
- 异常：无（参数缺省 = 不过滤）
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### GET /eval/badcases/{id} — 详情（含证据快照与归因）
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 存在一条 bad case 条目
- 成功：2xx + 响应含 question/evidence（JSONB 解析为对象）/triage/originRef
- 异常：id 不存在或非 badcase（source≠feedback）→ 404，message 含 `bad case 不存在`
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### PUT /eval/badcases/{id}/triage — 归因与领域确认（人工）
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过；domain 合并更新路径 2026-10-11 补充验证）
- 抽检: N
- 前置: 存在一条 DRAFT bad case
- 成功：2xx + `triage.auto=false` + `triage.stage` 为新值（规则不再覆盖）；body 带 `domain` 时一并更新领域（点踩时用户答不出领域，此处补，推荐值来自 evidence.hitCollections 命中集合）
- 异常：① stage 为空 → 400，含 `stage 不能为空`；② stage 非法枚举 → 400，含 `非法归因环节`；③ id 不存在 → 404
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### PUT /eval/badcases/{id}/status — 忽略 / 恢复
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 存在一条 DRAFT 或 IGNORED 的 bad case
- 成功：2xx + `badcaseStatus=IGNORED`（忽略）或 `DRAFT`（恢复）
- 异常：① 已转正（READY）→ 400，含 `已转正的 bad case 不能忽略`；② status 非 IGNORED/DRAFT → 400，含 `只允许 IGNORED（忽略）或 DRAFT（恢复）`；③ id 不存在 → 404
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### POST /eval/badcases/{id}/search-candidates — 转正辅助：候选检索
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 存在一条 bad case；目标数据集已关联文档集合且集合内已有向量
- 成功：2xx + 数组，元素含 `docId` / `docName` / `score` / `channel` / `preview`
- 异常：① 目标数据集不存在 → 404，含 `目标数据集不存在`；② id 不存在 → 404
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### POST /eval/badcases/{id}/draft-answer — 转正辅助：LLM 起草标准答案
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 存在一条 bad case；docIds 在向量库中有内容
- 成功：2xx + `{answer: "..."}`（LLM 基于勾选文档起草，语言随问题）
- 异常：① docIds 为空 → 400，含 `docIds 不能为空`；② 超过 5 篇 → 400，含 `一次最多基于 5 篇文档起草`；③ 文档在向量库无内容 → 400，含 `没有内容`
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### POST /eval/badcases/{id}/promote — 转正（补 ground truth → 搬领域数据集）
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: Y
- 前置: 存在一条 DRAFT bad case；目标领域数据集存在（名称 ≠ `badcase 归集池`）
- 成功：2xx + `badcaseStatus=READY` / `enabled=1` / `datasetId` 已变为目标；两数据集 item_count 一增一减；下轮评测可抽中该条
- 异常：① targetDatasetId 为空 → 400；② 目标不存在 → 404；③ 目标为归集池 → 400，含 `转正目标不能是归集池`；④ expectedDocIds 为空 → 400，含 `必须补 ground truth`；⑤ 条目非 DRAFT → 400，含 `只有 DRAFT`
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### GET /eval/badcases/stats — 分布统计（状态/环节/领域/渠道）
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 无（空库返回 total=0 与空 map）
- 成功：2xx + `{total, byStatus, byStage, byDomain, byOrigin}`；计数与列表数据一致
- 异常：无
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### GET /eval/badcases/domains — 领域字典
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 无
- 成功：2xx + 字符串数组（默认 `["接口文档","技术手册","行政制度"]`，可经 `rag.eval.badcase.domains` 配置扩展）
- 异常：无
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection

### GET /eval/badcases/feedback-flags — 批量已反馈标记
- 状态: ✅ 已验证@2026-10-11-2110-local（冒烟：成功路径 + 全部异常分支实测通过）
- 抽检: N
- 前置: 至少一条已归集的 bad case（其 origin_ref.messageId 已知）
- 成功：2xx + `{messageId: badcaseId}` 映射（未归集的 messageId 不出现在结果里）
- 异常：缺 messageIds 参数 → 400，message 含 `缺少必填参数`
- 兜底：统一错误体
- 来源/变更：2026-10-11 badcase-collection
