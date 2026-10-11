# api-storage — 对象存储接口预期

### POST /storage/upload — 上传文件到指定命名空间
- 状态: ✅ 已验证@2026-10-11-1809-local（200 + url/originalFilename/size/mimeType/detectedType 全对；缺 namespace→400；上传对象已自清理）
- 抽检: Y
- 前置: 无（fixture 文件 `.claude/pipeline/fixtures/sample.txt`，见 pipeline.yml `api.fixtures_dir`）
- 成功：2xx + 响应含 `url`（`<namespace>/<32位hex>.<ext>` 裸 object key）、`originalFilename`、`size`、`mimeType`、`detectedType`
- 异常：① 缺 namespace → 400，message 含 `缺少必填参数: namespace`；② 空文件 → 400，message 含 `上传文件不能为空`
- 兜底：未知错误 → 统一错误体 `{"code":500,"message":"服务器内部错误: …"}`（不裸堆栈）；>50MB → 413 `上传文件超过大小限制（最大 50MB）`
- 来源/变更：`docs/plans/2026-10-11-devloop-health-process.md` §四.4；改预期须用户确认

### POST /storage/upload/asset — 上传公共资产
- 状态: ✅ 已验证@2026-10-11-1809-local（200 + url(hex.txt 无前缀)；缺 file 部件→400「缺少上传文件部件: file」；上传对象已自清理）
- 抽检: Y
- 前置: 无（fixture 同上）
- 成功：2xx + 响应含 `url`（`<32位hex>.<ext>`，无 namespace 前缀）、`originalFilename`、`size`
- 异常：缺 file 部件 → 400，message 含 `缺少上传文件部件: file`
- 兜底：未知错误 → 统一错误体（不裸堆栈）；>50MB → 413
- 已知偏差：空文件不会 400（`Assert.notNull(content)` 对空 byte[] 通过，写入 0 字节对象）；2026-10-11 记录
- 来源/变更：同上；改预期须用户确认

### GET /storage/download — 下载对象
- 状态: ✅ 已验证@2026-10-11-1809-local（不存在 key→404「对象不存在: …」——RustFS 实测抛 NoSuchKeyException，捕获生效；缺 key→400「缺少必填参数: key」）
- 抽检: Y
- 前置: 成功场景需先上传一个对象拿 url（或使用现存对象 key）
- 成功：200 + `Content-Type: application/octet-stream` + 二进制流（与上传内容一致）
- 异常：① key 不存在 → 404，message 含 `不存在`；② 缺 key → 400，message 含 `缺少必填参数: key`
- 兜底：未知错误 → 统一错误体（不裸堆栈）
- 已实测排除：RustFS 对 miss 抛 NoSuchKeyException（2026-10-11 重验），原「泛 S3Exception」担忧未成真
- 来源/变更：`docs/plans/2026-10-11-devloop-health-process.md` §一.6、§五.5；改预期须用户确认
