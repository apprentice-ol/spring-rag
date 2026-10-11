# api-ingestion — 入库与流水线接口预期

### POST /ingestion/pipelines — 创建入库流水线
- 状态: ✅ 已验证@2026-10-11-1809-local（重名 409；nodes 省略(null) 与 []=空数组均 200——nodes null 容忍修复验证；测试数据已自清理）
- 抽检: Y
- 前置: 重名场景需库中已存在「冒烟测试」流水线（现存脏数据即种子）；成功场景用随机名 `smoke-<时间戳>`，测后自清理
- 成功：2xx + 响应含 `id`（Long）
- 异常：name 与已有流水线重名 → 409，message 含 `已存在，请换一个`
- 兜底：未知错误 → 统一错误体 `{"code":500,"message":"服务器内部错误: …"}`（不裸堆栈）
- 已知偏差：name=null 走 DB NOT NULL 违例（DataIntegrityViolationException 未注册）→ 兜底 500（理想为 400）；2026-10-11 记录
- 来源/变更：`docs/plans/2026-10-11-devloop-health-process.md` §一.6、§五.4；改预期须用户确认
