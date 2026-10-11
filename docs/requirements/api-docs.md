# api-docs — 文档入库接口预期

### POST /docs/upload — 同步上传文档并入库
- 状态: ✅ 已验证@2026-10-11-1809-local（首轮 FAILED 归因= fixture 45 字符纯英文命中 ChunkJunkFilter 短残片规则→已换含 CJK 样本；重测 200 + status=DONE + chunkCount=1）
- 抽检: Y
- 前置: 无（fixture 文件 `.claude/pipeline/fixtures/sample.txt`，见 pipeline.yml `api.fixtures_dir`）
- 成功：2xx + 响应含 `docId`、`name`、`chunkCount`（≥0）、`status` = `DONE`
- 异常：① 空文件 → 400，message 含 `上传文件为空`；② 缺 file 部件 → 400，message 含 `缺少上传文件部件: file`
- 兜底：未知错误 → 统一错误体 `{"code":500,"message":"服务器内部错误: …"}`（不裸堆栈）；>50MB → 413
- 注意：解析节点内失败（不支持的文件类型等）**不抛异常**——返回 200 + `status: FAILED`、`chunkCount: 0`（断言以 status 字段为准，不能只看 HTTP 码）
- 来源/变更：`docs/plans/2026-10-11-devloop-health-process.md` §四.4；改预期须用户确认

### POST /docs/upload-async — 异步上传文档（MQ 排队）
- 状态: ✅ 已验证@2026-10-11-1809-local（接口 200 + taskId/docId/PENDING；异步链终态 DONE chunks=1）
- 抽检: Y
- 前置: 无（fixture 同上）
- 成功：2xx + 响应含 `taskId`、`docId`、`status` = `PENDING`
- 异常：① 空文件 → 400，message 含 `上传文件为空`；② 缺 file 部件 → 400，message 含 `缺少上传文件部件: file`
- 兜底：未知错误 → 统一错误体（不裸堆栈）
- 来源/变更：同上；改预期须用户确认
