# 命令权限白名单 — 减少确认弹窗（已启用）

> **状态：已启用（2026-10-11）**。清单在 `.claude/settings.local.json` 的 `permissions.allow`（本文件为启用记录与维护约定）。

## 已启用（收窄版）

| 规则 | 说明 |
|---|---|
| `Bash(apifox *)` | Apifox CLI：同步 / 建用例 / 跑批 |
| `Bash(docker logs/ps/images *)` | 容器查看（只读） |
| `Bash(docker compose --project-directory . -f docker/… up *)` ×4 | compose 启动（本地双文件 / webtest / 纯沙盒 / 服务器） |
| `Bash(docker compose --project-directory . -f docker/… logs *)` ×2 | compose 日志 |
| `Bash(bash scripts/build-local.sh)` / `… --package` | 构建 / 打部署包 |

> **为什么不用 `docker compose *` 宽通配**：会连 `down -v`（删数据卷）一起免确认。
> **为什么按完整前缀写**：compose 已移入 `docker/`，命令固定带 `--project-directory .`（见 CLAUDE.md「仓库目录约定」）。

## 已清理（2026-10-11 启用时执行）

- `mvn -pl app ...` 系列 ×5（旧模块名，`app/` 模块已不存在）
- `20_customer-platform` 旧目录条目 ×2、一次性 `taskkill` 定值 PID、`awk` 片段
- 一次性命令：LiveRAG 导入 ×2、`unzip -l` ×2
- 无信息量 echo ×3
- 含明文 dev 凭据的 curl ×1
- （更早）`com.nageoffer.ai.rag` 固旧启动命令、`build-local.sh` 旧路径

## 维护约定

- 新增规则写**完整前缀**（中间通配符会触发 Claude Code 警告，且实际放行面被放大）
- 破坏性命令（`down -v`、`rm`、强制推送等）不进白名单
