# springai-rag 日志使用规范

> 本规范适用于 `rag-core` 模块。落地一套统一的日志/可观测性用法，保证：trace 能串、日志能查、字段能聚合、不爆量。

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
| `conversation_id` / `request_type` | `StreamChatPipeline.execute` | 会话/请求类型 |
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

- **日志流**：`springai_rag_logs`（**下划线**；collector 的 openobserve-logs exporter / `obs.openobserve.stream` 指定，查询必须用下划线）
- **按事件过滤**：`_event=llm.request` / `_event=step.output`
- **按步骤过滤**：`rag_step=rag.retrieve`
- **按 trace 反查**：`trace_id=<32位hex>`（对话诊断里用户发 traceId 即走此路）
- **trace 视图 Input/Output 面板**：读 span attribute `input`/`output`（不是日志），由 `StepSpan`/`LlmTraceAdvisor` 落

---

## 9. 配置

- **`logback-spring.xml`**：CONSOLE + OPEN_TELEMETRY 双 appender；OTLP 端点由 `application-obs.yaml` 的 `obs.collector.*` 桥接，OpenObserve 凭据走环境变量（`OPENOBSERVE_URL` / `OO_USERNAME` / `OO_PASSWORD`）
- **`application.yaml`**：`logging.level` 控制级别。当前开发期：`com.nageoffer.ai.rag: DEBUG`、`org.springframework.ai: DEBUG`、`DispatcherServlet: DEBUG`、`ibatis: DEBUG`
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
