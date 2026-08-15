# obs 埋点指南（典型情况）

> 适用 obs-telemetry 模块（`com.nageoffer.ai.obs`）。所有示例均来自本仓库真实代码，可直接对照。
> 日志层面的一般规范（级别/前缀/占位符/MDC）见 [logging-guide.md](logging-guide.md)（注意：该文旧命名未刷新，埋点部分以本文为准）。

## 0. 心智模型

一句话：**能用注解就用 `@ObservedStep`，注解够不到的（静态方法/同类内部调用）手动 `ObsTemplate.step`，只想记一条细节不开 span 就用 `log.event`**。

数据流向（埋点后自动发生，无需关心）：

```
@ObservedStep / ObsTemplate.step ──> ObsSpan ──> processor 链（摘要→截断）──> exporter 链
                                        │                                      ├─ span attribute input/output（OpenObserve trace 视图 / Langfuse）
                                        │                                      └─ 结构化日志 step.input/step.output（_event 可查）
                                        └ MDC step/step_id（嵌套自动保存/恢复，日志按 step_id 关联 span）
```

## 1. 典型情况速查

| # | 情况 | 用法 | 本仓库示例 |
|---|---|---|---|
| 1 | HTTP 入口（对话根） | `@ObservedConversation` + `@ObservedStep("rag.chat")` | `ChatController` |
| 2 | 同步 bean 步骤（首选） | `@ObservedStep("rag.xxx")` | `QueryRewriter.rewrite` |
| 3 | 流式 Flux 步骤 | `@ObservedStep(value, captureOutput=true)` | `RagAnswerStreamService.answer` |
| 4 | SSE emitter 方法 | `@ObservedStep`（切面自动挂完成回调） | `ChatController` /chat/stream |
| 5 | 静态工具 / 同类内部调用（AOP 盲区） | `ObsTemplate.getInstance().step(...)` | `StreamChatPipeline` 调 `QueryNormalizer.normalize` |
| 6 | 独立后台任务 trace | `@ObservedStep(kind=ROOT)` 或 `obsTemplate.openTrace(...)` | `EvalRunner`（eval.item） |
| 7 | 低基数标签（可聚合） | `obsTemplate.tag(...)` / `ObsSpan.tag(...)` | `EvalRunner`（eval.run_id） |
| 8 | 附属事件（不开 span） | `log.event(...)` / `ObsStructuredLog.emit(...)` | `BaiLianRerankClient`（rerank.scores） |
| 9 | 对话级最终回答 | `log.conversationOutput(...)`；流式回调用 `log.conversationSink()` | `StreamChatPipeline` |

---

## 2. 逐个展开

### 2.1 入口方法（对话根）—— `@ObservedConversation` + `@ObservedStep`

入口 Controller 方法同时标两个注解：`@ObservedStep` 开步骤 span，`@ObservedConversation` 捕获 HTTP 根 span、把用户问题设为 trace 级 input、conversationId 进 MDC。

```java
// ChatController
@ObservedStep("rag.chat")
@ObservedConversation
public SseEmitter chatStream(String question, String conversationId, ...) { ... }
```

- `conversationId` 为空时切面自动生成 UUID 并**回填入参**（业务与 trace 用同一个 id）
- 业务后续用 `log.conversationOutput` / `conversationSink` 写 trace 级 output（见 2.9）

### 2.2 同步 bean 步骤（首选）—— `@ObservedStep`

给 Spring bean 的 **public 方法**标注，切面自动：开子 span → input=入参摘要 → output=返回值摘要 → duration → 异常记录。方法体零改动。

```java
// QueryRewriter
@ObservedStep("rag.query.rewrite")
public String rewrite(String query, String historyContext) { ... }
```

- 有 `getName()` 的 bean，span 名自动展开：`@ObservedStep("rag.channel")` + `getName()="vector"` → `rag.channel.vector`（检索通道/后处理器用这招区分实现）
- 只对 **Spring 代理的 public 方法**生效；同类内部调用 `this.xxx()`、static、private 切不到 → 见 2.5

### 2.3 流式 Flux 步骤 —— `@ObservedStep(captureOutput=true)`

返回 `Flux` 的方法，span **延迟到首次订阅才开**（未被订阅的分支不留悬空 trace；重复订阅每次各开一个）。`captureOutput=true` 时累积完整输出原样记为 span output（不摘要，仍受 `obs.limits.max-span-io` 截断兜底）。

```java
// RagAnswerStreamService
@ObservedStep(value = "rag.answer", captureOutput = true)
public Flux<String> answer(String question, String contextText) { ... }
```

- LLM 调用本身由 Spring AI ChatModel observation 自动产生 `gen_ai` span（含 token 用量），无需手工埋
- 生产想收窄数据量：把 `captureOutput` 改回 `false`（只记 input + 耗时）

### 2.4 SSE emitter 方法 —— `@ObservedStep`

方法返回 `SseEmitter`/`ResponseBodyEmitter` 时，切面在 emitter 上挂 onCompletion/onTimeout/onError 回调自动关 span；`@ObservedConversation` 负责对话上下文（见 2.1）。

### 2.5 AOP 盲区：静态工具 / 同类内部调用 —— `ObsTemplate.step`

静态方法（如 rag-common 工具类）、`this.xxx()` 内部调用、private 方法，注解切面够不到——**在调用处手动包一层**：

```java
// StreamChatPipeline（rag-common 的静态工具，注解够不到）
String ruleNormalized = ObsTemplate.getInstance()
        .step("rag.query.normalize", question, () -> QueryNormalizer.normalize(question));
```

- `step(name, input, Supplier<T>)`：自动 input/output/计时/异常，语义与注解完全一致
- 高频复用的盲区（多处调用同一静态工具）可考虑把工具改成 bean 挂注解，一处埋点处处生效
- 手动变体：`openStep(name)` 拿 `ObsSpan` 句柄自管生命周期（跨方法/多次 output 等特殊场景，极少用）

### 2.6 独立后台任务 trace —— `kind=ROOT` / `openTrace`

无 HTTP 上下文的后台任务（MQ 消费、定时任务、eval 跑批）需要自己开 trace 根：

```java
// EvalRunner：注解式
@ObservedStep(value = "eval.item", kind = ObservedStep.Kind.ROOT)
...

// EvalRunner：句柄式（要 tag/traceInput）
try (ObsSpan root = obsTemplate.openTrace("eval.item")) {
    root.tag("eval.run_id", runId);
    root.traceInput(item.getQuestion());
    ...
}
```

> ⚠️ **ROOT = `setNoParent` 新开 trace**。在 HTTP 请求内使用会把链路断成两条 trace——请求链路内一律用默认 STEP。ROOT 只用于"本来就没有父"或"刻意脱钩"的场景。

### 2.7 低基数标签 —— `tag`

可枚举、可聚合的维度值（model/channel/命中与否）写 tag，**不要**写高基数值（userId/完整 query）进 tag：

```java
// EvalRunner（写当前 ambient span）
obsTemplate.tag("eval.paradigm", paradigm);
obsTemplate.tag("eval.hit", hit);

// 句柄上写（ROOT/手动 step）
root.tag("eval.run_id", runId);
```

### 2.8 附属事件（不开 span）—— `log.event`

只想给当前 step 加一条细节（中间数据/评分明细），不值得单独开 span：

```java
// BaiLianRerankClient：逐条 relevance_score，经 MDC step_id 自动关联到 rag.rerank.call 的 span
ObsStructuredLog.emit("rerank.scores",
        Map.of("model", model, "kept", kept, "dropped", dropped, "scores", scores));

// 业务类持有 ObsLogger 时首选：
log.event("rerank.scores", Map.of(...));
```

- **必须在某个 step 作用域内调用**（从 MDC 取 step/step_id），脱离 step 调用则不关联 trace
- OpenObserve 按 `_event=rerank.scores` 过滤，`step_id` 反查所属 span

### 2.9 对话级最终回答 —— `conversationOutput` / `conversationSink`

把最终回答写成 trace 级 output（OTel GenAI 标准属性 `gen_ai.output.messages`；OpenObserve LLM 视图原生识别，Langfuse 由 backends 模块兼容映射成 `langfuse.observation.output`）：

```java
// 同步场景（当前线程就是入口线程）
log.conversationOutput(answer);

// 流式场景：reactor 回调线程的 ambient 常未恢复，必须在 subscribe 前于业务线程捕获 sink
Consumer<Object> outputSink = log.conversationSink();
flux.subscribe(
        content -> { ... },
        ...,
        () -> outputSink.accept(fullAnswer.toString()));   // 回调里用捕获的引用
```

### 2.10 trace 级字段（会话/用户/维度）—— 自动提取 + baggage + 应用侧映射

**业务管道零维度调用**：维度不在业务代码里"申报"，由观测层从 step 返回值**自动提取**（`ObsDimensions`
启动期注册，类字面量 + 方法引用，领域对象重构时编译报错不静默失效）：

```java
// rag-core config/obs/ObsDimensions（唯一知道"哪个类型贡献哪些维度"的地方）
obsTemplate.dimensionOnOutput(IntentResult.class, r -> Map.of("intent", r.getIntent()));
obsTemplate.dimensionOnOutput(AgentRetrievalResult.class, r -> Map.of("agent", r.trace().getParadigm()));
```

触发链：`@ObservedStep` 方法返回时 → obs 按注册表匹配返回值类型 → `traceDimension` 落键
（{ns}.trace.metadata.* + 汇总 tags，{ns}=`obs.attribute-namespace` 默认 spring.application.name）→ backends 映射为 `langfuse.trace.metadata.*` 等后端字段。

| 通用 key | 来源 | Langfuse 呈现 |
|---|---|---|
| `session.id`（OTel 标准） | `@ObservedConversation` 切面自动（conversationId 进 baggage） | Sessions 视图按会话分组整个对话（原生识别） |
| `user.id`（OTel 标准） | 业务在入口 `obsTemplate.user(uid)`（当前无登录态，预留） | 用户视图/按人聚合（原生识别） |
| `{ns}.trace.tags` / `{ns}.trace.metadata.*` | **自动提取**（dimensionOnOutput） | 列表过滤 / 一级可过滤 metadata |
| `gen_ai.client.response.time_to_first_token`（OTel GenAI 标准，秒） / `{ns}.first_token_at`（时间戳） | `decorateFlux` 自动（流式 step 首 token） | 世代视图 TTFT |
| `gen_ai.input/output.messages` | `beginConversation` / `conversationOutput`（OTel GenAI 标准，OpenObserve 原生识别） | trace 级 IO 面板 |
| `gen_ai.request.model` | 自研模型调用标 `obsTemplate.model(name)`（通用动词） | span 识别为 generation，按模型聚合 |

机制：入口写一次 **OTel Baggage**（`ObsTemplate.baggage`，随 Context 跨线程传播），`BaggageAttributeSpanProcessor`
把 baggage 落为 trace 内**所有** span 的属性（含 Spring AI 建的 gen_ai span）。默认 W3C propagator
不外发 baggage，值不会泄漏给下游第三方 API。属性 key 的后端兼容映射在**应用内**完成
（`obs.langfuse.attribute-mapping.enabled` 开关），与 collector 是否在场无关。

**业务代码出现以下任何一处都算泄漏**：观测属性 key 字面量（`gen_ai.*` / `session.id` / `{ns}.*`）、
除 `obsTemplate.model()` / `obsTemplate.user()` 之外的观测方法调用（`step`/`stream` 除外）。

**应用永远只写 OTel 标准通用 key**；无标准的概念用命名空间前缀（`{ns}` = `obs.attribute-namespace`，
默认 `spring.application.name`）；后端不认标准的地方（仅 Langfuse）由 obs-telemetry-backends 的
`LangfuseAttributeKeyMapper` 在**应用内**追加 `langfuse.*`——与 collector 是否在场无关
（`obs.collector.enabled` 两种模式行为一致；`obs.langfuse.attribute-mapping.enabled=false` 可停用）。
OTel 标准字段、生态通用字段（`gen_ai.prompt` / `gen_ai.completion` 等大多数框架沿用的 key）一律收口在
`OtelKeys` 常量，框架/业务代码禁止写属性 key 字面量；其余自定义字段沿用命名空间前缀约定。
trace 级输入/输出由 obs-telemetry 直接写 OTel GenAI 标准属性 `gen_ai.input/output.messages`
（OpenObserve 拍平为 `gen_ai_input/output_messages` 列，LLM 视图原生识别，零映射）；
Langfuse 不认识该新标准字段，由 `LangfuseAttributeKeyMapper` 兼容映射为
`langfuse.observation.input/output`——即"应用只写 OTel 标准，后端不兼容处各自做兼容"。

> **OpenObserve 已知限制（2026-08-15 核实，暂不升级）**：Traces 列表页使用的 `latest_stream` 聚合接口目前只返回
> `gen_ai_input_messages` 等字段，不返回 `gen_ai_output_messages`，前端也没有把该列映射到表格行，
> 所以列表里手动加的 `gen_ai_output_messages` 列恒为空；span 详情 / AI → LLM 视图 / SQL 查询都能看到输出。
> 这是 OpenObserve 0.91.x 的 UI/聚合接口限制，不是应用采集或 collector 的问题。
> 已核对上游最新源码：v0.92.0（2026-08-07）/ v0.92.1 的 `useTraces.ts#formatTracesMetaData()` 仍只映射
> `gen_ai_input_messages`，列表聚合对象也没有 output 字段——**该问题上游未修复，升级 OpenObserve 无效**。
> 当前 5080 端口的容器（`public.ecr.aws/zinclabs/openobserve:latest`，0.91.x）与 compose 定义的
> `rag-openobserve` 不是同一容器，后续如需升级需先统一启动方式。决定：暂不升级，待上游修复后评估。

> **Langfuse 已知限制（2026-08-15 记录，暂不升级）**：当前 `langfuse/langfuse:3`（3.225.2）Traces 表格的
> Input/Output 单元格显示空白/截断——表格批量 IO 读取默认走 `events_core`，该表把 input/output/metadata
> 预截断为 200 字符，行高 Medium/Large 时内容基本不显示。**数据本身完整**（API / trace 详情 / span 均有
> input/output，已验证 trace `6ad1c5ab5f92dccc9e2e8442d668e015`）。上游 PR langfuse#15875（2026-08-07）
> 已修复，但仅包含在 **v4.7.0+**，未 backport 到 v3（最新 v3.225.3 仍无此修复）。决定：暂不升级
> （v4 为大版本、涉及迁移），如需恢复表格完整 IO 显示需升级 Langfuse v4.7+。

**logs / metrics 同规则**：MDC（经 OpenTelemetryAppender 落为 OTLP 日志属性）——会话用标准
`session.id`（与 trace 同 key），步骤用 `{ns}.step` / `{ns}.step_id`（无标准，命名空间）；结构化日志
body 的 `_event/step/step_id/data/duration_ms` 是文档化私有 schema（OTel 不约束 body 内容），保持稳定。
metrics——LLM 指标（`gen_ai.client.operation.duration` / `gen_ai.client.token.usage`）由 Spring AI
原生按 GenAI 标准输出；框架自有指标（步骤耗时 Timer）用 `{ns}.step.duration`（tag=步骤名，低基数）。
全部 key/指标名收口在 `OtelKeys`。

| 通用 key | 谁写 | Langfuse 呈现 |
|---|---|---|
| `session.id`（OTel 标准） | `@ObservedConversation` 切面自动（conversationId 进 baggage） | Sessions 视图按会话分组整个对话（原生识别） |
| `user.id`（OTel 标准） | 业务在入口 `obsTemplate.user(uid)`（当前无登录态，预留） | 用户视图/按人聚合（原生识别） |
| `{ns}.trace.tags` | obs 内部（`obsTemplate.traceDimension` 落键，业务只给键值） | 列表按 tag 过滤 |
| `{ns}.trace.metadata.*` | obs 内部（同上） | 一级可过滤 metadata（注意普通 attribute 会掉进不可过滤的 `metadata.attributes`） |
| `gen_ai.client.response.time_to_first_token` / `{ns}.first_token_at` | `decorateFlux` 自动（流式 step 首 token） | 世代视图 TTFT / completionStartTime |
| `gen_ai.input/output.messages` | `beginConversation` / `conversationOutput`（OTel GenAI 标准，OpenObserve 原生识别） | trace 级 IO 面板 |
| `gen_ai.request.model` | 业务 `tag`（如 rerank 的模型名） | span 识别为 generation，按模型聚合 |

机制：入口写一次 **OTel Baggage**（`ObsTemplate.baggage`，随 Context 跨线程传播），`BaggageAttributeSpanProcessor`
把 baggage 落为 trace 内**所有** span 的属性（含 Spring AI 建的 gen_ai span）——这是 Langfuse 官方要求的
trace 级字段传播方式。默认 W3C propagator 不外发 baggage，值不会泄漏给下游第三方 API。

### 2.11 后端读取/编排客户端（obs-telemetry-backends）

数据面统一走 OTel，后端“特有动作”收口在 obs-telemetry-backends，业务不直接写 HTTP：

| 客户端 | 配置前缀 | 提供能力 |
|---|---|---|
| `LangfuseApiClient`（实现 `LangfuseDatasetClient` / `LangfuseScoreClient` 接口） | `obs.langfuse`（auth / public-key+secret-key） | `listDatasetItems` / `linkRunItem` / `submitScore` / `listRuns`——数据集 run 关联与评分写回 |
| `OpenObserveQueryClient` | `obs.openobserve`（url / username+password） | `searchLogsByTraceId` / `searchSpansByTraceId`——按 traceId 查日志与 span 链路 |

约定（长期保持）：

- 客户端是**条件 Bean**：未配置凭据或开关关闭时不注册；调用方用 `ObjectProvider` / `Optional` 注入，避免启动失败。
- **不进采集热路径**：只服务编排/查询；OpenObserve 查询失败返回空列表不抛异常，Langfuse 调用失败抛
  `LangfuseApiException`，由调用方决定降级策略。
- DTO 与客户端同模块（`langfuse/dto`、`openobserve/dto`），业务侧不新建同名 DTO，保持单一来源。
- 编排（跑问答 → 关联 run item → 打分写回）留在应用层/脚本，框架只提供原语。

使用示例见独立模块 `obs-telemetry-examples`（可编译、可运行只读演示；写操作以参考代码 + README 说明接入）。

---

## 3. span 命名规范

统一前缀 `rag.<阶段>.<子步骤>`（eval 用 `eval.*`）：

| 阶段 | span 名 |
|---|---|
| 查询归一化/改写/拆解 | `rag.query.normalize` / `rag.query.rewrite` / `rag.query.decompose` |
| 意图 | `rag.intent.classify` |
| Agent 编排 | `rag.agent.retrieve` / `rag.agent.grade` |
| 检索 | `rag.retrieve` / `rag.channel.<name>`（getName 展开） |
| 后处理 | `rag.postproc`（getName 展开为 dedup/fusion/rerank/...） |
| 重排 | `rag.rerank.call` |
| 回答 | `rag.answer` / `rag.chitchat` |
| 评测 | `eval.item` 等 |

## 4. 防膨胀与限额

- input/output 默认经 `Summarizer` 递归摘要（字符串截 200、集合记 size+前 3 条）；`captureOutput`/`outputRaw` 走原文但受 `max-span-io`（默认 20000）截断兜底
- 全部可配（启动期生效）：`application-obs.yaml` 的 `obs.limits.*`（`max-span-io` / `summarize-max-string` / `summarize-max-preview` / `summarize-max-map-entries`）
- **禁止**把完整 payload（整篇文档、全部 chunks、完整 prompt）塞进日志/attribute——要记大对象先 `Summarizer.summarize(obj)`

## 5. 常见坑

1. **注解只对 Spring 代理的 public bean 方法生效**——内部调用/static/private 用 2.5 的手动方式
2. **`kind=ROOT` 会断链**——请求内别用（见 2.6）
3. **`log.event` 必须在 step 作用域内**，否则取不到 MDC 的 step/step_id，事件成了孤儿
4. **流式 conversation output 必须先捕获 sink**（`conversationSink()` 在 subscribe 前调），回调线程里现取常为 null
5. **别手动管 MDC**——step/step_id 由切面/后端自动写入并在嵌套时保存/恢复；跨线程由 context-propagation 自动承担（线程池要挂 `ContextPropagatingTaskDecorator`）
6. **别把高基数值写进 tag**（应写 attribute/input）——tag 是低基数聚合维度
7. **AOP 入参摘要用真实参数名**依赖编译 `-parameters`（spring-boot-starter-parent 默认已开，自建父 pom 注意保留）
