# 可观测性（设计 · 埋点 · 日志规范）

> 2026-10-11 文档整理：本文由三份文档合并而成，原文完整保留，各部分以 `---` 分隔。
>
> - 第一部分 · 可观测性设计：业务只面向 OTel/Micrometer，后端可插拔
> - 第二部分 · telemetry 埋点指南（典型情况、span 命名、input/output 契约）
> - 第三部分 · 日志使用规范（结构化事件日志与普通业务日志）

---

<!-- 原文件：docs/opentelemetry设计思路.md -->

# 可观测性设计：业务只面向 OTel/Micrometer，后端可插拔

> 目标：业务代码只依赖 OpenTelemetry + Micrometer；OpenObserve / Langfuse 只是「导出目标」，
> 换存储/展示后端只要它支持 OTLP，就只加配置、不改业务代码。

## 1. 总体数据流

```
业务代码（@TraceStep / RagTelemetry / RagLogger）
        │  只写通用语义属性：
        │    gen_ai.prompt / gen_ai.completion   （OTel GenAI 语义约定）
        │    rag.trace.input / rag.trace.output  （对话 trace 级 I/O，项目自定义）
        │    rag.step / input / output           （步骤 span 属性）
        │  + MDC（traceId/spanId/rag_step/step_id/llm_*）
        ▼
OTLP/HTTP → otel-collector (docker/otel-collector/otel-collector-config.yaml)
        │  每个后端一条独立 pipeline：
        ├── traces/openobserve → otlphttp/openobserve
        └── traces/langfuse   → transform/langfuse → otlphttp/langfuse
                                （把 rag.trace.* 映射为 langfuse.observation.*）
        ▼
   OpenObserve / Langfuse / 任意支持 OTLP 的新后端
```

应用代码里**没有**任何 Langfuse / OpenObserve 专属字段；后端专属映射全部下沉到 collector。

## 2. 应用侧契约

### 2.1 埋点入口（业务最小侵入）

- 声明式：Service 方法加 `@TraceStep("rag.xxx")`，由 `TraceStepAspect` 自动开/关 span、记 input/output。
- 手动式（AOP 盲区）：`RagTelemetry.step(...)` / `RagTelemetry.stream(...)` / `RagTelemetry.startRoot(...)`。
- 日志：`RagLogger.of(X.class)` 的 `info/warn/error/event`，自动携带 MDC 的 traceId/spanId。

### 2.2 对话 trace 级 input/output（唯一需要业务显式参与的地方）

- input：`ConversationTraceAdvisor` 在 `ChatController.stream` 入口捕获 HTTP 根 span，
  `RagTelemetry.startConversation(conversationId, question)` 写一次 `rag.trace.input`。
- output：每个产生最终回答的分支，在回答完成点显式调用一次：

```java
ConversationTrace trace = log.currentConversationTrace(); // 请求线程或订阅前捕获引用
...
if (trace != null) {
    trace.output(answer);   // 写 rag.trace.output，线程安全
}
```

流式分支必须在 `subscribe` **之前**捕获引用，再在 `onComplete` 里使用（Spring AI 的
boundedElastic 回调线程不保证 ThreadLocal 上下文）。`saveMessage` 只做消息持久化，不再碰 trace。

### 2.3 框架耦合点（可配置）

| 类 | 用途 | 开关 |
| :--- | :--- | :--- |
| `ChatModelCompletionContentObservationFilter` | 把 prompt/completion 原文写成 OTel `gen_ai.prompt/completion` | `rag.observability.llm.content-attributes` |
| `LlmTraceAdvisor` | 记模型参数/token 用量到 span/MDC + 结构化日志 | `rag.observability.llm.usage-attributes` |

这两个是唯一与 Spring AI 类型耦合的埋点。换 AI 框架时关掉这两个开关，新框架自带/自建
Micrometer 观测即可，其余 `@TraceStep` / `RagTelemetry` 全部不受影响。

## 3. 后端可插拔（collector 侧）

`docker/otel-collector/otel-collector-config.yaml` 里每个后端是一条独立 pipeline：

```yaml
service:
  pipelines:
    traces/openobserve:
      receivers: [otlp]
      processors: [filter/drop-noise, batch]
      exporters: [otlphttp/openobserve]
    traces/langfuse:
      receivers: [otlp]
      processors: [filter/drop-noise, batch]
      exporters: [otlphttp/langfuse]
```

> 注：早期版本在 collector 里有 `transform/langfuse` processor（OTTL 做 rag.* → langfuse.* 映射），
> 现已**上收应用侧**（llm-observability-backends 的 `LangfuseAttributeKeyMapper`，与 collector 是否在场无关），
> collector 只做扇出/过滤/批量，见配置文件头注释。

### 新增/替换后端三步（无需改业务代码）

1. 在 `exporters` 加一个 `otlphttp/<后端名>`（endpoint + 认证 headers）。
2. 若该后端需要专属字段语义（rag.* → 后端字段映射），在**应用侧**实现属性映射器（参考 `LangfuseAttributeKeyMapper`）；collector 内不再放 transform。
3. 在 `service.pipelines` 加一条 pipeline，或在已有 pipeline 的 exporters 列表里增删。

## 4. 跨线程上下文

- `ContextPropagationConfig` 注册 MDC / OTel Context / `ConversationTrace` 三个 accessor，
  并开启 Reactor 自动传播；虚拟线程入口用 `ContextSnapshot.captureAll().wrap(...)`。
- 线程池（检索通道）用 Spring 的 `ContextPropagatingTaskDecorator`。
- 原则：**凡是异步完成点要写 span 数据的，先捕获对象引用再跨线程用**，不依赖 ThreadLocal 恢复。

## 5. 日志（已迁 OTLP Logs）

日志已由 logback 的 `OpenTelemetryAppender` 统一走 **OTLP Logs**（→ collector logs pipeline → OpenObserve，
`telemetry.collector.enabled=false` 时直连 OpenObserve `/v1/logs`），旧的 `OpenObserveAppender` 直推 `_json` API
的实现已删除。日志诊断读取（`OpenObserveQueryClient`）仍直连 OpenObserve SQL——后端耦合仅剩这一处，与 trace 无关。

## 6. 升级后验证清单

- 编译启动后发一轮对话：Langfuse trace 表 input/output 应有值（应用侧 `LangfuseAttributeKeyMapper` 映射生效，collector 无需 transform）。
- 每条 trace latency 应为秒级，不出现几百秒、新 trace 挂旧 trace（旧 scope 泄漏已修）。
- OpenObserve trace 视图行为不变（`rag.chat`/步骤 span 的 input/output 依旧）。
- 关掉 `rag.observability.llm.*` 两个开关后：trace 结构不变，仅缺失 LLM 用法/原文属性。

---

<!-- 原文件：docs/llm-observability-guide.md -->

# telemetry 埋点指南（典型情况）

> 适用 llm-observability 模块（`com.jjx.ai.llmobservability`）。所有示例均来自本仓库真实代码，可直接对照。
> 日志层面的一般规范（级别/前缀/占位符/MDC）见本文第三部分「customer-platform 日志使用规范」（注意：该部分旧命名未刷新，埋点部分以第二部分为准）。

## 0. 心智模型

一句话：**能用注解就用 `@TelemetryStep`，注解够不到的（静态方法/同类内部调用）手动 `TelemetryTemplate.step`，只想记一条细节不开 span 就用 `log.event`**。

数据流向（埋点后自动发生，无需关心）：

```
@TelemetryStep / TelemetryTemplate.step ──> TelemetrySpan ──> processor 链（摘要→截断）──> exporter 链
                                        │                                      ├─ span attribute input/output（OpenObserve trace 视图 / Langfuse）
                                        │                                      └─ 结构化日志 step.input/step.output（_event 可查）
                                        └ MDC step/step_id（嵌套自动保存/恢复，日志按 step_id 关联 span）
```

## 1. 典型情况速查

| # | 情况 | 用法 | 本仓库示例 |
|---|---|---|---|
| 1 | HTTP 入口（对话根） | `@TelemetryConversation` + `@TelemetryStep("rag.chat")` | `ChatController` |
| 2 | 同步 bean 步骤（首选） | `@TelemetryStep("rag.xxx")` | `QueryRewriter.rewrite` |
| 3 | 流式 Flux 步骤 | `@TelemetryStep(value, captureOutput=true)` | `RagAnswerStreamService.answer` |
| 4 | SSE emitter 方法 | `@TelemetryStep`（切面自动挂完成回调） | `ChatController` /chat/stream |
| 5 | 静态工具 / 同类内部调用（AOP 盲区） | `TelemetryTemplate.getInstance().step(...)` | `ChatOrchestrator` 调 `QueryNormalizer.normalize` |
| 6 | 独立后台任务 trace | `@TelemetryStep(kind=ROOT)` 或 `obsTemplate.openTrace(...)` | `EvalRunner`（eval.item） |
| 7 | 低基数标签（可聚合） | `obsTemplate.tag(...)` / `TelemetrySpan.tag(...)` | `EvalRunner`（eval.run_id） |
| 8 | 附属事件（不开 span） | `log.event(...)` / `TelemetryStructuredLog.emit(...)` | `BaiLianRerankClient`（rerank.scores） |
| 9 | 对话级最终回答 | `log.conversationOutput(...)`；流式回调用 `log.conversationSink()` | `ChatOrchestrator` |

---

## 2. 逐个展开

### 2.1 入口方法（对话根）—— `@TelemetryConversation` + `@TelemetryStep`

入口 Controller 方法同时标两个注解：`@TelemetryStep` 开步骤 span，`@TelemetryConversation` 捕获 HTTP 根 span、把用户问题设为 trace 级 input、conversationId 进 MDC。

```java
// ChatController
@TelemetryStep("rag.chat")
@TelemetryConversation
public SseEmitter chatStream(String question, String conversationId, ...) { ... }
```

- `conversationId` 为空时切面自动生成 UUID 并**回填入参**（业务与 trace 用同一个 id）
- 业务后续用 `log.conversationOutput` / `conversationSink` 写 trace 级 output（见 2.9）

### 2.2 同步 bean 步骤（首选）—— `@TelemetryStep`

给 Spring bean 的 **public 方法**标注，切面自动：开子 span → input=入参摘要 → output=返回值摘要 → duration → 异常记录。方法体零改动。

```java
// QueryRewriter
@TelemetryStep("rag.query.rewrite")
public String rewrite(String query, String historyContext) { ... }
```

- 有 `getName()` 的 bean，span 名自动展开：`@TelemetryStep("rag.channel")` + `getName()="vector"` → `rag.channel.vector`（检索通道/后处理器用这招区分实现）
- 只对 **Spring 代理的 public 方法**生效；同类内部调用 `this.xxx()`、static、private 切不到 → 见 2.5

### 2.3 流式 Flux 步骤 —— `@TelemetryStep(captureOutput=true)`

返回 `Flux` 的方法，span **延迟到首次订阅才开**（未被订阅的分支不留悬空 trace；重复订阅每次各开一个）。`captureOutput=true` 时累积完整输出原样记为 span output（不摘要，仍受 `telemetry.limits.max-span-io` 截断兜底）。

```java
// RagAnswerStreamService
@TelemetryStep(value = "rag.answer", captureOutput = true)
public Flux<String> answer(String question, String contextText) { ... }
```

- LLM 调用本身由 Spring AI ChatModel observation 自动产生 `gen_ai` span（含 token 用量），无需手工埋
- 生产想收窄数据量：把 `captureOutput` 改回 `false`（只记 input + 耗时）

### 2.4 SSE emitter 方法 —— `@TelemetryStep`

方法返回 `SseEmitter`/`ResponseBodyEmitter` 时，切面在 emitter 上挂 onCompletion/onTimeout/onError 回调自动关 span；`@TelemetryConversation` 负责对话上下文（见 2.1）。

### 2.5 AOP 盲区：静态工具 / 同类内部调用 —— `TelemetryTemplate.step`

静态方法（如 platform-common 工具类）、`this.xxx()` 内部调用、private 方法，注解切面够不到——**在调用处手动包一层**：

```java
// ChatOrchestrator（platform-common 的静态工具，注解够不到）
String ruleNormalized = TelemetryTemplate.getInstance()
        .step("rag.query.normalize", question, () -> QueryNormalizer.normalize(question));
```

- `step(name, input, Supplier<T>)`：自动 input/output/计时/异常，语义与注解完全一致
- 高频复用的盲区（多处调用同一静态工具）可考虑把工具改成 bean 挂注解，一处埋点处处生效
- 手动变体：`openStep(name)` 拿 `TelemetrySpan` 句柄自管生命周期（跨方法/多次 output 等特殊场景，极少用）

### 2.6 独立后台任务 trace —— `kind=ROOT` / `openTrace`

无 HTTP 上下文的后台任务（MQ 消费、定时任务、eval 跑批）需要自己开 trace 根：

```java
// EvalRunner：注解式
@TelemetryStep(value = "eval.item", kind = TelemetryStep.Kind.ROOT)
...

// EvalRunner：句柄式（要 tag/traceInput）
try (TelemetrySpan root = obsTemplate.openTrace("eval.item")) {
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
TelemetryStructuredLog.emit("rerank.scores",
        Map.of("model", model, "kept", kept, "dropped", dropped, "scores", scores));

// 业务类持有 TelemetryLogger 时首选：
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

**业务管道零维度调用**：维度不在业务代码里"申报"，由观测层从 step 返回值**自动提取**（`TelemetryDimensions`
启动期注册，类字面量 + 方法引用，领域对象重构时编译报错不静默失效）：

```java
// app config/telemetry/TelemetryDimensions（唯一知道"哪个类型贡献哪些维度"的地方）
obsTemplate.dimensionOnOutput(IntentResult.class, r -> Map.of("intent", r.getIntent()));
obsTemplate.dimensionOnOutput(AgentRetrievalResult.class, r -> Map.of("agent", r.trace().getParadigm()));
```

触发链：`@TelemetryStep` 方法返回时 → telemetry 按注册表匹配返回值类型 → `traceDimension` 落键
（{ns}.trace.metadata.* + 汇总 tags，{ns}=`telemetry.attribute-namespace` 默认 spring.application.name）→ backends 映射为 `langfuse.trace.metadata.*` 等后端字段。

| 通用 key | 来源 | Langfuse 呈现 |
|---|---|---|
| `session.id`（OTel 标准） | `@TelemetryConversation` 切面自动（conversationId 进 baggage） | Sessions 视图按会话分组整个对话（原生识别） |
| `user.id`（OTel 标准） | 业务在入口 `obsTemplate.user(uid)`（当前无登录态，预留） | 用户视图/按人聚合（原生识别） |
| `{ns}.trace.tags` / `{ns}.trace.metadata.*` | **自动提取**（dimensionOnOutput） | 列表过滤 / 一级可过滤 metadata |
| `gen_ai.client.response.time_to_first_token`（OTel GenAI 标准，秒） / `{ns}.first_token_at`（时间戳） | `decorateFlux` 自动（流式 step 首 token） | 世代视图 TTFT |
| `gen_ai.input/output.messages` | `beginConversation` / `conversationOutput`（OTel GenAI 标准，OpenObserve 原生识别） | trace 级 IO 面板 |
| `gen_ai.request.model` | 自研模型调用标 `obsTemplate.model(name)`（通用动词） | span 识别为 generation，按模型聚合 |

机制：入口写一次 **OTel Baggage**（`TelemetryTemplate.baggage`，随 Context 跨线程传播），`BaggageAttributeSpanProcessor`
把 baggage 落为 trace 内**所有** span 的属性（含 Spring AI 建的 gen_ai span）。默认 W3C propagator
不外发 baggage，值不会泄漏给下游第三方 API。属性 key 的后端兼容映射在**应用内**完成
（`telemetry.langfuse.attribute-mapping.enabled` 开关），与 collector 是否在场无关。

**业务代码出现以下任何一处都算泄漏**：观测属性 key 字面量（`gen_ai.*` / `session.id` / `{ns}.*`）、
除 `obsTemplate.model()` / `obsTemplate.user()` 之外的观测方法调用（`step`/`stream` 除外）。

**应用永远只写 OTel 标准通用 key**；无标准的概念用命名空间前缀（`{ns}` = `telemetry.attribute-namespace`，
默认 `spring.application.name`）；后端不认标准的地方（仅 Langfuse）由 llm-observability-backends 的
`LangfuseAttributeKeyMapper` 在**应用内**追加 `langfuse.*`——与 collector 是否在场无关
（`telemetry.collector.enabled` 两种模式行为一致；`telemetry.langfuse.attribute-mapping.enabled=false` 可停用）。
OTel 标准字段、生态通用字段（`gen_ai.prompt` / `gen_ai.completion` 等大多数框架沿用的 key）一律收口在
`AttributeKeys` 常量，框架/业务代码禁止写属性 key 字面量；其余自定义字段沿用命名空间前缀约定。
trace 级输入/输出由 llm-observability 直接写 OTel GenAI 标准属性 `gen_ai.input/output.messages`
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
全部 key/指标名收口在 `AttributeKeys`。

| 通用 key | 谁写 | Langfuse 呈现 |
|---|---|---|
| `session.id`（OTel 标准） | `@TelemetryConversation` 切面自动（conversationId 进 baggage） | Sessions 视图按会话分组整个对话（原生识别） |
| `user.id`（OTel 标准） | 业务在入口 `obsTemplate.user(uid)`（当前无登录态，预留） | 用户视图/按人聚合（原生识别） |
| `{ns}.trace.tags` | telemetry 内部（`obsTemplate.traceDimension` 落键，业务只给键值） | 列表按 tag 过滤 |
| `{ns}.trace.metadata.*` | telemetry 内部（同上） | 一级可过滤 metadata（注意普通 attribute 会掉进不可过滤的 `metadata.attributes`） |
| `gen_ai.client.response.time_to_first_token` / `{ns}.first_token_at` | `decorateFlux` 自动（流式 step 首 token） | 世代视图 TTFT / completionStartTime |
| `gen_ai.input/output.messages` | `beginConversation` / `conversationOutput`（OTel GenAI 标准，OpenObserve 原生识别） | trace 级 IO 面板 |
| `gen_ai.request.model` | 业务 `tag`（如 rerank 的模型名） | span 识别为 generation，按模型聚合 |

机制：入口写一次 **OTel Baggage**（`TelemetryTemplate.baggage`，随 Context 跨线程传播），`BaggageAttributeSpanProcessor`
把 baggage 落为 trace 内**所有** span 的属性（含 Spring AI 建的 gen_ai span）——这是 Langfuse 官方要求的
trace 级字段传播方式。默认 W3C propagator 不外发 baggage，值不会泄漏给下游第三方 API。

### 2.11 后端读取/编排客户端（llm-observability-backends）

数据面统一走 OTel，后端“特有动作”收口在 llm-observability-backends，业务不直接写 HTTP：

| 客户端 | 配置前缀 | 提供能力 |
|---|---|---|
| `LangfuseApiClient`（实现 `LangfuseDatasetClient` / `LangfuseScoreClient` 接口） | `telemetry.langfuse`（auth / public-key+secret-key） | `listDatasetItems` / `linkRunItem` / `submitScore` / `listRuns`——数据集 run 关联与评分写回 |
| `OpenObserveQueryClient` | `telemetry.openobserve`（url / username+password） | `searchLogsByTraceId` / `searchSpansByTraceId`——按 traceId 查日志与 span 链路 |

约定（长期保持）：

- 客户端是**条件 Bean**：未配置凭据或开关关闭时不注册；调用方用 `ObjectProvider` / `Optional` 注入，避免启动失败。
- **不进采集热路径**：只服务编排/查询；OpenObserve 查询失败返回空列表不抛异常，Langfuse 调用失败抛
  `LangfuseApiException`，由调用方决定降级策略。
- DTO 与客户端同模块（`langfuse/dto`、`openobserve/dto`），业务侧不新建同名 DTO，保持单一来源。
- 编排（跑问答 → 关联 run item → 打分写回）留在应用层/脚本，框架只提供原语。

使用示例见独立模块 `llm-observability-examples`（可编译、可运行只读演示；写操作以参考代码 + README 说明接入）。

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

## 4. input/output 契约（落哪些键、哪个后端认）

每个 span 每方向**只写一套键**——两套同值键只会让两个后端各落一列重复值。分工如下（2026-10-11 在本机 Langfuse 逐键探针实测过摄取规则）：

| span 层级 | 写入方 | input 键 | output 键 | 读它的地方 |
|---|---|---|---|---|
| 对话根（HTTP server span） | `ConversationContext`（`@TelemetryConversation` 触发） | `gen_ai.input.messages`（消息数组）+ `langfuse.observation.input`（mapper 追加） | `gen_ai.output.messages` + `langfuse.observation.output` | Langfuse trace 级 IO、OpenObserve trace 列表 |
| 任意步骤 span（step / node / tool / agent） | `@TelemetryStep` 切面 / `TelemetryTemplate.step` / `MicrometerTracerAdapter` | `input`（摘要 JSON） | `output`（摘要 JSON） | OpenObserve Trace 输入/输出面板（原生字段）；Langfuse observation 列（原生识别裸键） |
| chat model span | Spring AI + `ChatModelCompletionObservationHandler` | `gen_ai.prompt`（消息数组） | `gen_ai.completion` | Langfuse GENERATION input/output |
| embedding span | `EmbeddingObservationHandler` | `gen_ai.prompt` | `gen_ai.completion`（向量形状摘要） | 同上 |

- **序列化统一走 `ObservationJson`**——全库唯一 Gson 出口（`java.time` 落 ISO-8601、不可反射的 JDK 内部类型跳字段兜底）。观测对象普遍带时间字段，`new Gson()` 在 JDK 17+ 上会让整个摘要降级成 `{type,error}` 占位（真实 trace 上 node:、workflow:、tool: 三类 span 曾全部中招）
- **两种 IO 语义不互换**：对话级是消息数组（`[{"role","content"}]`），步骤级是摘要对象/原文——步骤级不要借 `gen_ai.*.messages` 键（那是对话语义），对话级不要塞裸 `input`（保留给步骤语义）
- **会话身份**：全程只写 `session.id`（OTel 标准，入口 baggage 注入到 trace 内每个 span；实测 Langfuse 对 `session.id` / `langfuse.session.id` / `gen_ai.conversation.id` 三键都落 session 列）。引擎内部会话（agent-core 的 session）写 `engine.session.id`，勿与用户对话 id 混用

## 5. 防膨胀与限额

- input/output 默认经 `Summarizer` 递归摘要（字符串截 200、集合记 size+前 3 条）；`captureOutput`/`outputRaw` 走原文但受 `max-span-io`（默认 20000）截断兜底
- 全部可配（启动期生效）：`application-telemetry.yaml` 的 `telemetry.limits.*`（`max-span-io` / `summarize-max-string` / `summarize-max-preview` / `summarize-max-map-entries`）
- **禁止**把完整 payload（整篇文档、全部 chunks、完整 prompt）塞进日志/attribute——要记大对象先 `Summarizer.summarize(obj)`

## 6. 常见坑

1. **注解只对 Spring 代理的 public bean 方法生效**——内部调用/static/private 用 2.5 的手动方式
2. **`kind=ROOT` 会断链**——请求内别用（见 2.6）
3. **`log.event` 必须在 step 作用域内**，否则取不到 MDC 的 step/step_id，事件成了孤儿
4. **流式 conversation output 必须先捕获 sink**（`conversationSink()` 在 subscribe 前调），回调线程里现取常为 null
5. **别手动管 MDC**——step/step_id 由切面/后端自动写入并在嵌套时保存/恢复；跨线程由 context-propagation 自动承担（线程池要挂 `ContextPropagatingTaskDecorator`）
6. **别把高基数值写进 tag**（应写 attribute/input）——tag 是低基数聚合维度
7. **AOP 入参摘要用真实参数名**依赖编译 `-parameters`（spring-boot-starter-parent 默认已开，自建父 pom 注意保留）

---

<!-- 原文件：docs/logging-guide.md -->

# customer-platform 日志使用规范

> 本规范适用于 `app` 模块。落地一套统一的日志/可观测性用法，保证：trace 能串、日志能查、字段能聚合、不爆量。

---

## 1. 可观测性架构

三层，统一汇入 **OpenObserve**：

```
                              ┌── OTLP /traces ──► OpenObserve (trace 视图)
OTel 分布式追踪 (span)        │
(@TraceStep / RagTelemetry)   │   span 的 input/output attribute
                              │   由 StepSpan 持有的 Span 引用落 attribute（不用 Span.current()，流式回调线程不可靠）
                              │
结构化事件日志 (logger=        ├── OTLP /logs ───► OpenObserve (日志流 springai_rag_logs)
  rag.telemetry)              │   经 logback OpenTelemetryAppender → OTLP Logs（MDC 随日志属性带出）
(StructuredLog.emit)          │   (_event/rag_step/step_id/data/duration_ms)
                              │
普通业务日志 (slf4j)  ─────────┘   自动带 MDC 的 traceId/spanId（OTel 注入）
(log.info/debug/warn/error)       → 与 trace 按 trace_id/span_id 关联
```

**关联锚点**：结构化日志的 `step_id` = OTel span 的 `spanId` → 点 trace 里的 span 能跳到对应结构化日志。

---

## 2. 日志分两类，按场景选

| | 结构化事件日志 | 普通业务日志 |
|---|---|---|
| **产生方式** | `@TraceStep` / `RagTelemetry` / `StructuredLog.emit` | `log.info/debug/warn/error` |
| **logger** | `rag.telemetry`（固定） | 业务类的 `log`（slf4j） |
| **OpenObserve 呈现** | 顶层字段（`_event`/`rag_step`/`step_id`/`data`...），可过滤聚合 | `message` 字段 + MDC 平铺字段 |
| **用于** | 步骤事件（检索/LLM/重排的输入输出、耗时、token）、需要查询/聚合的指标 | 人工排查的过程信息、关键决策点、错误堆栈 |
| **payload** | 经 `Summarizer` 递归摘要（防膨胀） | 原样输出 |

**原则**：能用结构化埋点的步骤事件，就用结构化；只在「给人看的过程/排错信息」时用普通 log。**不要用普通 log 打大 payload**（整篇文档、完整检索结果）——那是结构化 + Summarizer 的活。

---

## 3. 结构化埋点规范

### 3.1 首选 `@TraceStep` 注解（同步方法）

给 Spring bean 的 **public 方法**标注，AOP 自动开 child span、记 input/output、记耗时：

```java
@TraceStep("rag.retrieve")
public RetrievalResult retrieve(SearchContext context) { ... }
```

- 有 `getName()` 的 bean，span 名自动展开：`@TraceStep("rag.channel")` + `getName()="vector"` → span `rag.channel.vector`
- `input` = 方法入参摘要（`Summarizer.summarizeArgs`，**用真实参数名**，需编译带 `-parameters`）
- `output` = 返回值摘要，`duration_ms` 自动记
- **限制**（见注意事项）：仅 Spring 代理的 public 方法；同类内部调用、静态方法、流式（`Flux.subscribe`）切不到

### 3.2 手动埋点用 `RagLogger`（统一 log 入口，替代 `@Slf4j`）

业务类用 `RagLogger` 替代 `@Slf4j`——一个 `log` 对象同时支持普通日志和步骤埋点，写法和 `log.xxx` 习惯一致：

```java
private static final RagLogger log = RagLogger.of(MyClass.class);  // 替代 @Slf4j

public void doSomething() {
    log.info("[模块] 命中={}条", n);                          // 普通日志，自动带 traceId/spanId
    String r = log.step("rag.xxx", input, () -> ...);            // 同步步骤（自动 input/output/close + error）
    log.stream("rag.answer", input, flux, true).subscribe(...);  // 流式步骤（captureOutput=true 记完整回答）
    log.event("rag.xxx.detail", Map.of("k", v));                 // 附属事件（不开 span，挂当前 step）
}
```

API（三类埋点 + 普通日志）：

| API | 开 span | 用途 |
|---|---|---|
| `log.step(name, input, Supplier<T>)` | ✅ 同步 | 同步步骤：开 span + input + output(返回值) + 计时 ★ **同步步骤首选** |
| `log.step(name, Supplier<T>)` / `log.step(name, input, Runnable)` | ✅ 同步 | 同步 span 变体（不记 IO / 无返回值）|
| `log.stream(name, input, Flux<T>, captureOutput)` | ✅ 流式 | 流式步骤：开 span + input + finish/error；`captureOutput=true` 累积流内容为完整 output ★ **流式步骤首选** |
| `log.stream(name, input, Flux<T>)` | ✅ 流式 | 同上但不记 output（向后兼容）|
| `log.event(event, data)` | ❌ | **附属事件**：不开 span，挂在当前 step 上记一条细节（rerank.scores / llm.request 等）|
| `log.info/debug/warn/error` | ❌ | 普通业务日志（委托 slf4j）|
| `log.step(name)` | ✅ | 拿 `StepSpan` 手动管（极少用，跨方法/多次 output 等特殊场景）|

**怎么选**：要进 trace 时间线的一个步骤（有起止、要计时、有 IO）→ 同步用 `log.step`/`@TraceStep`，流式用 `log.stream`；只是给当前 step 加一条备注/中间数据，不想单独开 span → `log.event`；给人看的过程/排错信息 → `log.info/debug/...`。

> `RagLogger` 内部：`info/debug` 委托 per-class slf4j Logger；`step/stream` 委托 `RagTelemetry` 单例；`event` 委托 `StructuredLog`。无需直接注入 `RagTelemetry`。

### 3.3 事件命名规范

统一前缀 `rag.<阶段>.<子步骤>`（channel/postprocessor 的 span 名第三段由 bean 的 `getName()` 自动展开，是否生效取决于 `isEnabled()`）：

| 阶段 | 示例 span 名 |
|---|---|
| 查询归一化/改写/拆解 | `rag.query.normalize` / `rag.query.rewrite` / `rag.query.decompose` |
| 意图 | `rag.intent.classify` |
| Agent 编排 | `rag.agent.retrieve` / `rag.agent.grade` |
| 检索 | `rag.retrieve` / `rag.channel.vector` / `rag.channel.keyword` |
| 后处理 | `rag.postproc.dedup` / `rag.postproc.doc-scope` / `rag.postproc.fusion` / `rag.postproc.rerank` / `rag.postproc.mmr-diversity` |
| 重排 | `rag.rerank.call` |
| 回答 | `rag.answer` / `rag.chitchat` |

自定义事件（非 step.input/output）用 `log.event` / `StructuredLog.emit`——两者都自动从 MDC 取 step/stepId，关联到当前所在 step 的 span（**必须在一个 step/stream 作用域内调用**，否则不关联 trace）：

```java
// 业务类（持有 RagLogger）—— 首选
log.event("rerank.scores", Map.of("scores", scoreList));

// 框架组件 / 非 RagLogger 类
StructuredLog.emit("rerank.scores", Map.of("scores", scoreList));   // 自动取 MDC
// 完整签名（需自定义 step/stepId/duration 时才用）：
// StructuredLog.emit(event, step, stepId, data, durationMs)
```

> 不要再手写 `MDC.get("rag_step"), MDC.get("step_id")`——旧写法，已收口进 `emit(event, data)` 重载。

---

## 4. 普通业务日志规范

- **级别**：`error`（异常/失败）、`warn`（可恢复异常/降级）、`info`（关键节点）、`debug`（细节，默认开）
- **前缀**：用 `[模块]` 前缀便于 grep，如 `log.info("[对话管道] 检索完成: 命中={}条", n)`
- **占位符**：始终用 `{}` 占位符，**不要字符串拼接**（性能 + 避免 NPE）
- **异常**：`log.error("[模块] xxx 失败", e)` 把异常对象作最后一个参数（打印堆栈）
- **自动带 trace**：每行自动带 `[traceId,spanId]`（OTel 注入 MDC），**不要手动拼 traceId 进消息**

---

## 5. MDC 字段约定

MDC 字段由 logback 的 `OpenTelemetryAppender` 捕获为日志属性、随 OTLP Logs 带到 OpenObserve，可过滤/聚合。**字段名用下划线**：

| 字段 | 谁写入 | 用途 |
|---|---|---|
| `traceId` / `spanId` | OTel 自动 | trace 关联 |
| `rag_step` / `step_id` | `RagTelemetry.step` | 当前步骤名 + spanId |
| `conversation_id` / `request_type` | `ChatOrchestrator.execute` | 会话/请求类型 |
| `llm_model` / `llm_role` / `llm_temperature` / `llm_max_tokens` | `LlmTraceAdvisor.before` | LLM 调用参数 |
| `llm_prompt_tokens` / `llm_completion_tokens` / `llm_total_tokens` | `LlmTraceAdvisor.after` | LLM token 用量 |

> **不要往 MDC 塞大对象或敏感信息**（密码、完整 prompt）。MDC 是 ThreadLocal Map，每条日志都会平铺。

---

## 6. 跨线程（已自动化，别手动 wrap）

虚拟线程、`ragContextExecutor`、Reactor Flux 的 MDC + OTel Context 传播**已由 `ContextPropagationConfig` 自动承担**（注册 SLF4J + OTel accessor + `Hooks.enableAutomaticContextPropagation`）。

- **不要**手动捕获/恢复 MDC（旧 `ContextPropagator` 已删，手写只会冗余）
- 新增并发点：
  - 线程池 → 用 Spring `ThreadPoolTaskExecutor` 并 `setTaskDecorator(new ContextPropagatingTaskDecorator())`
  - 虚拟线程 → `Thread.ofVirtual().start(ContextSnapshot.captureAll().wrap(() -> ...))`
  - Flux → 自动传播，无需处理

---

## 7. 防膨胀（Summarizer）

结构化日志的 `data` 和 span 的 `input`/`output` attribute **默认经 `Summarizer` 递归摘要**：

- String 截断（默认 200 字符）
- Collection/Array → `{size, preview:[前3条]}`
- bean → Gson 转 JsonElement 后递归摘要（字段展开，深层集合记 size+preview）
- 超长 JSON 降级为完整的 `{_truncated:true, length:N}`（**绝不 substring 截断中间**，否则 OpenObserve 解析失败）

**例外——流式完整 output（debug 用）**：`log.stream(name, input, flux, true)` 的 `captureOutput=true` 走 `outputRaw`，**不摘要**——完整 LLM 回答原样落 span output attribute（受 `MAX_SPAN_IO=20000` 字符上限兜底，超长降级 `_truncated`）。落 attribute 时 String 存原文（保留真换行，非 GSON 转义），非 String 才走 `toJsonTruncated`。开发 debug 阶段清晰优先；上线想收窄把 `captureOutput` 改回 `false`。

**禁止**把完整 payload（整篇文档、全部 chunks、完整 prompt）直接塞进日志/attribute。要记大对象，先 `Summarizer.summarize(obj)` 或 `Summarizer.toJsonTruncated(obj, max)`。

---

## 8. OpenObserve 查询

- **日志流**：`springai_rag_logs`（**下划线**；collector 的 openobserve-logs exporter / `telemetry.openobserve.stream` 指定，查询必须用下划线）
- **按事件过滤**：`_event=llm.request` / `_event=step.output`
- **按步骤过滤**：`rag_step=rag.retrieve`
- **按 trace 反查**：`trace_id=<32位hex>`（对话诊断里用户发 traceId 即走此路）
- **trace 视图 Input/Output 面板**：读 span attribute `input`/`output`（不是日志），由 `StepSpan`/`LlmTraceAdvisor` 落

---

## 9. 配置

- **`logback-spring.xml`**：CONSOLE + OPEN_TELEMETRY 双 appender；OTLP 端点由 `application-telemetry.yaml` 的 `telemetry.collector.*` 桥接，OpenObserve 凭据走环境变量（`OPENOBSERVE_URL` / `OO_USERNAME` / `OO_PASSWORD`）
- **`application.yaml`**：`logging.level` 控制级别。当前开发期：`com.jjx.customer.platform: DEBUG`、`org.springframework.ai: DEBUG`、`DispatcherServlet: DEBUG`、`ibatis: DEBUG`
- **trace 采样**：`management.tracing.sampling.probability: 1.0`（全采样，开发期；生产可调低）

---

## 10. 注意事项 / 坑

1. **`@TraceStep` 只对 Spring 代理的 public bean 方法生效**。同类内部调用（`this.xxx()`）、private/静态方法、流式（`Flux.subscribe`）切不到——改用 `RagTelemetry.step` 手动埋点。
2. **流式 span 用 `log.stream(name, input, flux, captureOutput)`**。`captureOutput=true` 时内部 `doOnNext` 累积完整回答、`doFinally` 自动 `outputRaw` + `finish`，业务体不用碰 `StepSpan`；`false`（或三参重载）只记 input + 耗时。**不要**对返回的 Flux 再手动 `output()`——会和内部累积重复。
3. **trace 视图 Input/Output 读 span attribute `input`/`output`**，由 `StepSpan` 落（`input()` 同步落、`close()`/`finish()` 落 output）。**attribute 挂在 `StepSpan` 构造期捕获的 `Span` 引用上，不是 `Span.current()`**——流式 `finish()` 在 Reactor 回调线程跑，`Span.current()` 不保证恢复为本步骤 span（曾导致流式 output 落空、面板长期 No data available）。`LlmTraceAdvisor` 在同步栈仍用 `Span.current()`（安全）。
4. **LLM 的 `chat deepseek-chat` span 由 Spring AI ChatModel 自动建**，其 input/output 由 Spring AI 控制（本项目不干预）。要看 LLM 输入，点 **`llm-trace` span**（本项目 advisor 的 observation，input=prompt 摘要）。
5. **MDC 字段名用下划线**，别用驼峰（OpenObserve 关联字段 `trace_id`/`span_id` 是下划线；驼峰 `traceId` 也会平铺但下划线是标准）。
6. **`MAX_SPAN_IO`（span attribute 上限）当前 20000，demo 阶段清晰优先**。后续要截断调小 `StepSpan.MAX_SPAN_IO` / `LlmTraceAdvisor.MAX_IO`；要完全关闭 span attribute（只留日志），删 `setSpanIo` / `Span.current().setAttribute` 调用即可。
7. **别手动捕获/恢复 MDC 跨线程**。已由 `Hooks.enableAutomaticContextPropagation` + accessor 自动传播；手写 `MDC.getCopyOfContextMap()` 恢复是冗余（旧的 `StepSpan.mdcSnapshot` 已移除）。
8. **大对象进日志前必须 `Summarizer`**。直接 `log.info("ctx={}", hugeContext)` 或 `s.output(bigBean)` 会让日志/span 膨胀。
9. **生产环境**调低 `logging.level`（项目包 `INFO`、框架包 `WARN`）、`sampling.probability`（如 0.1），避免日志量过大。
