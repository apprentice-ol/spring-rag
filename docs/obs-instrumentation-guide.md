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

把最终回答写成 trace 级 output（Langfuse/OpenObserve trace 的 Output 面板，collector 会把 `rag.trace.output` 映射成 `langfuse.observation.output`）：

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
