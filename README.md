# springai-rag

基于 **Spring AI 1.1** 的 RAG 平台：文档入库 + 多通道检索 + Rerank 精排 + 流式问答。

## 项目说明（学习复刻 demo）

本项目的**检索与文档入库**能力复刻自 **[ragent](https://gitee.com/nageoffer/ragent.git)** 项目，用 **Spring AI 生态**（ChatClient / EmbeddingModel / VectorStore / ChatMemory / Advisor）替代原 ragent 的 infra-ai 层（屏蔽模型供应商），是对 ragent 检索管线与入库编排的学习实践。

- **原项目仓库**：
  - Gitee：https://gitee.com/nageoffer/ragent.git
  - GitHub：https://github.com/nageoffer/ragent.git
- **定位**：本项目为**学习复刻的 demo**，非 ragent 官方版本；采用 Spring AI 技术栈重写，代码结构与编排方式与原项目不同。

## 构建

```bash
cd /d/04_projects/20_springai-rag && bash build-local.sh
```

## 模块结构（自包含）

```
com.nageoffer.ai.rag
├── common/         通用基础（exception / context）
├── config/         Spring AI 装配 + 属性（ChatClientConfig / ChatProperties / IngestionProperties / OpenApiConfig）
├── ingestion/      ★ 入库模块（parser / chunk / engine / node / domain / dao / service / controller / mq / storage）
├── chat/           ★ 查询模块（retrieval / postprocessor / service / controller）
├── store/mapper/   MyBatis Mapper
├── model/          entity / dto
└── controller/     PingController
```

**Spring AI 扮演原 ragent 的 infra-ai 角色**（ChatClient / EmbeddingModel / VectorStore / ChatMemory / Advisor），不建 infra-ai 模块。详见 [CLAUDE.md](./CLAUDE.md)。

## 技术栈

- Java 21 · Spring Boot 3.5.7 · Spring AI 1.1.2
- PostgreSQL + pgvector（PgVectorStore）· Redis（缓存）· RocketMQ（异步入库）· MyBatis Plus
- 模型：DeepSeek（chat，全局）+ 阿里云百炼 text-embedding-v3（embedding，1024 维，OpenAI 兼容端点）
- 前端：Vue3 + TS + Ant Design Vue · Knife4j 接口文档 · Micrometer OTel（traceId）

## 运行

前置：PostgreSQL + pgvector（复用 ragent 库或自建 + 执行 `src/main/resources/sql/init.sql`）、Redis、DeepSeek + 百炼 API Key。

```bash
export DEEPSEEK_API_KEY=sk-xxxx   # yaml 已带默认值，可省
mvn spring-boot:run                # IDEA 启动注意 Shorten command line = JAR manifest（依赖多）
```

验证：
- **Knife4j**：`http://localhost:9081/api/rag/doc.html`
- **上传**：`curl -F "file=@xxx.md" http://localhost:9081/api/rag/docs/upload`
- **流式问答**：前端 `cd frontend && npm run dev` → `http://localhost:5173`

## 阶段进度

### 入库（ingestion 模块）
- [x] **P1** 引擎骨架（链式引擎 + 条件评估 + 6 节点 stub + DB pipeline 表 + 默认 pipeline Bootstrap）
- [ ] **P2** DB pipeline + Fetcher + Parser（DB/Block 模型就绪；**Parser/Fetcher 当前 stub，待真实化**）
- [ ] **P3** Chunker 全套（Block-Aware）+ IndexerNode 完整
- [ ] **P4** Enhancer/Enricher（LLM 增强）
- [ ] **P5** MinerU + RustFS（结构化解析 + 图片资产）
- [ ] **P6** VLM 多模态
- [ ] **P7** 节点日志持久化 + 默认 pipeline 种子

### 查询链（chat 模块）
- [x] 查询 MVP（ChatClient + QuestionAnswerAdvisor 单路 + SSE，**临时**，Q5 替换）
- [ ] **Q1** 归一化（术语映射 + 问题改写）
- [ ] **Q2** 意图识别（IntentTree）
- [ ] **Q3** 多通道检索（VectorChannel + Keyword 预留）
- [ ] **Q4** 后处理 + 重排（Dedup / RRF / Rerank）
- [ ] **Q5** StreamChatPipeline 编排（替换单路 QuestionAnswerAdvisor）

> 当前状态：5 个入库节点是 stub（Fetcher/Parser/Enhancer/Chunker/Enricher），IndexerNode 已真实接 PgVectorStore；查询是单路 MVP。

## 完整规划

见 plan 文件（`~/.claude/plans/rippling-stargazing-reddy.md`）：结构化整理方案 + 入库 P2-P7 + 查询链 Q1-Q5 完整路线。
