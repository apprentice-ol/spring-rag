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
      processors: [filter/drop-noise, transform/langfuse, batch]
      exporters: [otlphttp/langfuse]
```

### 新增/替换后端三步（无需改业务代码）

1. 在 `exporters` 加一个 `otlphttp/<后端名>`（endpoint + 认证 headers）。
2. 若该后端需要专属字段语义，在 `processors` 加一个 `transform/<后端名>`（OTTL 映射）。
3. 在 `service.pipelines` 加一条 pipeline，或在已有 pipeline 的 exporters 列表里增删。

Langfuse 的映射示例（collector 里，应用无感）：

```yaml
transform/langfuse:
  error_mode: ignore
  trace_statements:
    - context: span
      statements:
        - set(attributes["langfuse.observation.input"], attributes["rag.trace.input"]) where attributes["rag.trace.input"] != nil
        - set(attributes["langfuse.observation.output"], attributes["rag.trace.output"]) where attributes["rag.trace.output"] != nil
```

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

- 编译启动后发一轮对话：Langfuse trace 表 input/output 应有值（collector 的 transform 生效）。
- 每条 trace latency 应为秒级，不出现几百秒、新 trace 挂旧 trace（旧 scope 泄漏已修）。
- OpenObserve trace 视图行为不变（`rag.chat`/步骤 span 的 input/output 依旧）。
- 关掉 `rag.observability.llm.*` 两个开关后：trace 结构不变，仅缺失 LLM 用法/原文属性。
