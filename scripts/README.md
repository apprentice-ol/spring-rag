# scripts/ — 构建、部署与运维脚本

用法与前置见各脚本头部注释；构建/部署与升级全流程见 [../docs/deploy.md](../docs/deploy.md)。

## 构建 / 部署（主链路）

| 脚本 | 在哪执行 | 用途 |
|---|---|---|
| `build-local.sh` | 本地 | **唯一构建入口**：前端 dist 进 jar → 瘦 jar + lib/；`--package` 额外打「全新服务器部署包」（一次性物料，落 dist/） |
| `deploy-prepare.py` | 本地 | 部署准备：校验语料 → 生成 ensure-schema.sql → 导出 dump + 校验清单（data/deploy/，gitignore） |
| `deploy-upgrade.sh` | **服务器** | **部署唯一入口**，两种模式：日常升级（默认；`--corpus` 可选换语料）；`--full-restore --dump` 全量换库（清空全库再导入） |

## 结构对齐 / 运维 SQL

| 文件 | 用途 |
|---|---|
| `ensure-schema.sql` | 幂等 ALTER 合集（deploy-prepare.py 生成、deploy-upgrade.sh 应用）——给旧库补缺失列 |
| `eval-recall-diagnosis.sql` | 评测召回诊断（只读；指定 run_id 查检索失败原因） |

## 运维 / 数据工具（按需）

| 脚本 | 用途 |
|---|---|
| `check-secrets.sh` | pre-commit 钩子：拦截敏感信息提交（`git config core.hooksPath .githooks` 启用） |
| `seed-oo-log.sh` | 往 OpenObserve 灌一条业务异常日志，验收 ops 诊断的日志反查链路 |
| `oo_trace_query.py` | 按 traceId 查 OpenObserve 的 trace（诊断辅助；凭据走 .env 的 `OO_AUTH`） |
| `import-eval-history.py` | 从 docs/snapshots CSV 回填评测**运行级**历史（幂等；与快照数据配套保留） |
| `rebuild-eval-badcases.py` | 从 OO 日志重建历史评测逐题检索证据、捞 badcase |
