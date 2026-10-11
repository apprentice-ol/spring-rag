# docs 文档中心

项目文档库。**权威架构说明**：[architecture.md](architecture.md)；**平台实现详解**：[platform-deep-dive.md](platform-deep-dive.md)。

## 核心文档

| 文档 | 内容 |
|---|---|
| [architecture.md](architecture.md) | 架构与结构规范：① 项目结构与命名规范（16 模块分层）② Agent 内核 / 诊断业务 / 数据流转流程图 ③ Agent/Chat 层重建规格（功能验收基准） |
| [platform-deep-dive.md](platform-deep-dive.md) | RAG + Agent 平台实现详解（复杂文档处理 / 检索链路 / Agent 编排 / 评测与可观测；附 LiveRAG 评测集构成） |
| [observability.md](observability.md) | 可观测性：① 设计思路（OTel/Micrometer，后端可插拔）② telemetry 埋点指南 ③ 日志使用规范 |
| [deploy.md](deploy.md) | 部署与升级：① 打包部署指南（首次部署 / 日常更新 / OpenObserve OOM 排查）② 升级部署手册（语料迁移 / 回滚 / 全库替换） |
| [interview-prep.md](interview-prep.md) | 面试准备（项目问答口径）＋ 个人简历模板（附录） |

## 计划与设计归档（plans/）

按主题合并的归档，内部按时间排序、原文完整保留：

| 归档 | 主题与覆盖范围 |
|---|---|
| [plans/platform-refactor.md](plans/platform-refactor.md) | 平台重构（2026-09-12）：Agent×Workflow 目标态 · Workflow 框架化 · Prompt 能力包 · 模块化拆分进度 |
| [plans/ingestion-and-retrieval.md](plans/ingestion-and-retrieval.md) | 入库与检索质量（2026-07-29 → 09-18）：入库链路诊断 · 检索层修复 · 召回优化 · 小块检索大块生成 |
| [plans/context-and-core.md](plans/context-and-core.md) | 上下文与内核（2026-09-19 → 09-20）：上下文与会话生命周期 · Task 层 · RAG 多轮上下文 · 核心收敛 |
| [plans/hitl-and-task-qa.md](plans/hitl-and-task-qa.md) | 人在环与任务问答（2026-09-19 → 09-20）：HITL 设计 · 诊断入口顺序 · Task QA · 追问循环闭合 |

## 资产

| 路径 | 内容 |
|---|---|
| [images/](images/) | 截图与示意图 |
| [dashboards/](dashboards/) | OpenObserve dashboard JSON |
| [snapshots/](snapshots/) | 评测跑批结果 CSV 快照 |
| [spring-bean-lifecycle.drawio](spring-bean-lifecycle.drawio) | Spring Bean 生命周期图（drawio 源文件） |

> Docker 编排细节（compose 差异、端口、账号、表结构迁移、运维）见 [../docker/README.md](../docker/README.md)（唯一入口）。
