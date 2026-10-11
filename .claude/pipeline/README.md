# .claude/pipeline/ — 研发流水线专属目录（单一职责）

流水线（全局 dev-loop / build / api-test / deploy / web-test skill）的**唯一配置与台账位置**。
2026-10-11 起从 `.claude/` 根收拢到本目录；`.claude/` 根只保留 Claude Code 原生配置（settings.local.json）。

## 内容

| 内容 | 用途 |
|---|---|
| [pipeline.yml](pipeline.yml) | 项目适配文件：构建 / 启动 / 接口 / 部署参数（全局 skill 读取；旧位置 `.claude/pipeline.yml` 兼容） |
| [reports/](reports/) | 流水线执行台账与证据（runs/ 每次执行一目录；机制见 reports/README.md） |
| [apifox/](apifox/) | api-test 用例工作库（endpoints / 用例集 / manifest；由全局 apifox-gen-cases.py 生成） |
| [optional-permissions.md](optional-permissions.md) | 高频命令权限白名单建议（可选项，未启用） |

## 常用入口

- **跑全流程**：对 Claude 说"跑流水线"（dev-loop skill 读取本目录 pipeline.yml）
- **项目接入 / 初始化**：pipeline-init skill（自动创建本目录结构与各目录用途说明）
- **运行报告落盘**：`reports/runs/<runId>/`（runId = `<YYYY-MM-DD-HHmm>-<target>`）
