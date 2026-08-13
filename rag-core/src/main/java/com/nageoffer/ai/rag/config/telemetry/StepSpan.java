package com.nageoffer.ai.rag.config.telemetry;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.MDC;

/**
 * 一个 RAG 步骤的 span 句柄。两种底层来源（对调用方透明）：
 *
 * <ul>
 *   <li><b>Observation 模式</b>（{@link RagTelemetry#step(String)} 创建）：普通 step span，
 *       已 start + openScope，spanId 已写入 MDC。</li>
 *   <li><b>OTel 直开模式</b>（{@link RagTelemetry#startRoot(String)} 创建）：无父的 trace 根 span，
 *       内部走 OTel {@code SpanBuilder.setNoParent()} + {@code makeCurrent()}，不经 micrometer Observation。
 *       用于评测每 item 独立 trace 等「强制新 trace」场景。</li>
 * </ul>
 *
 * <p>两种关闭方式（幂等）：
 *
 * <ul>
 *   <li>{@link #close()}（try-with-resources，同步）：在发起线程关 scope + stop + 还原 MDC。
 *       AOP 切面 / 同步手动步骤用。调用链：{@code input(..)} → 业务 → {@code output(..)} → close。</li>
 *   <li>{@link #finish()}（流式异步）：在 Flux 回调线程记 output + stop；MDC/trace 由 Reactor
 *       自动传播恢复，scope 不关。流式 span 经 outputRaw 记完整 output，调用链：{@code input(..)} → 业务 → finish。</li>
 * </ul>
 *
 * <p>{@code close/finish} 触发 {@code step.output} 结构化日志（含 duration_ms）并 stop span。</p>
 */
public class StepSpan implements AutoCloseable {

    /** span/trace attribute 安全字符上限（OpenObserve/Langfuse 单字段防膨胀）。
     *  全口径单一来源：StepSpan input/output、LlmTraceAdvisor、RootSpanIO、ChatModelCompletionContent 均引用。 */
    public static final int MAX_SPAN_IO = 20000;

    /** Observation 模式专用；root（OTel 直开）模式为 null。 */
    private final Observation observation;
    private final Observation.Scope scope;
    /** root（OTel 直开）模式专用；Observation 模式为 null。 */
    private final io.opentelemetry.context.Scope otelScope;
    private final String spanId;
    private final String name;
    private final long startMs;
    private Object outputData;
    /** true 时 output 原样记录（不走 Summarizer 截断），流式步骤捕获完整 LLM 回答用。 */
    private boolean rawOutput;
    private boolean completed;
    /** step span 的 OTel 引用。input/output attribute 直接挂它，避免依赖 {@link Span#current()}：
     *  流式 {@link #finish()} 在 Reactor 回调线程，{@code Span.current()} 不保证恢复为本步骤 span。 */
    private final Span span;

    /** Observation 模式构造。 */
    StepSpan(Observation observation, Observation.Scope scope, String spanId, String name, Span span) {
        this.observation = observation;
        this.scope = scope;
        this.otelScope = null;
        this.spanId = spanId;
        this.name = name;
        this.span = span;
        this.startMs = System.currentTimeMillis();
    }

    /** OTel 直开（root）模式构造：span 已 setNoParent + start + makeCurrent（otelScope 由调用方 openScope）。 */
    StepSpan(String name, String spanId, Span span, io.opentelemetry.context.Scope otelScope) {
        this.observation = null;
        this.scope = null;
        this.otelScope = otelScope;
        this.spanId = spanId;
        this.name = name;
        this.span = span;
        this.startMs = System.currentTimeMillis();
    }

    /** 设置低基数 span 属性（model/channel/hits 等可枚举值，进 OpenObserve 可聚合）。
     *  root 模式退化为普通 setAttribute（如 eval.item_id/run_id）。 */
    public StepSpan attr(String key, Object value) {
        String v = value == null ? "" : value.toString();
        if (observation != null) {
            observation.lowCardinalityKeyValue(key, v);
        } else {
            span.setAttribute(key, v);
        }
        return this;
    }

    /** 记录步骤输入（立即发 step.input 结构化日志，已摘要），并落当前 span 的 input attribute。 */
    public StepSpan input(Object input) {
        Object summary = Summarizer.summarize(input);
        StructuredLog.emit("step.input", name, spanId, summary, null);
        setSpanIo("input", summary);
        return this;
    }

    /** 把摘要挂到 step span 的 input/output attribute（OpenObserve trace Input/Output 面板读此）。
     *  用构造期捕获的 {@link #span} 引用而非 {@link Span#current()}：close() 在发起线程二者等价，
     *  但 finish() 在 Reactor 回调线程，{@code Span.current()} 不保证恢复为本步骤 span，引用则稳。
     *  <p>String（如流式完整 LLM 回答）直接存原文（保留真换行），避免 GSON 序列化把 {@code \n}
     *  转义成字面量、加引号导致 OpenObserve 面板不可读；其余类型走 {@link Summarizer#toJsonTruncated} 紧凑 JSON。 */
    private void setSpanIo(String key, Object summary) {
        try {
            String value;
            if (summary instanceof CharSequence cs) {
                String s = cs.toString();
                value = s.length() <= MAX_SPAN_IO ? s : s.substring(0, MAX_SPAN_IO) + "…(truncated)";
            } else {
                value = Summarizer.toJsonTruncated(summary, MAX_SPAN_IO);
            }
            span.setAttribute(key, value);
        } catch (Exception ignored) {
            // span 已结束或设置失败，忽略（结构化日志仍已记录）
        }
    }

    /** 记录步骤输出（暂存，close/finish 时随 step.output 发出，走 Summarizer 200字摘要）。 */
    public StepSpan output(Object output) {
        this.outputData = output;
        return this;
    }

    /** 记录步骤输出并标记原样保留（不走 Summarizer 截断）。流式步骤捕获完整 LLM 回答用，
     *  close/finish 时仍受 {@code setSpanIo} 的 MAX_SPAN_IO 字符上限兜底。 */
    public StepSpan outputRaw(Object output) {
        this.outputData = output;
        this.rawOutput = true;
        return this;
    }

    /** trace 级 IO attribute key（与 {@link ConversationTrace} 同 key，OTel Collector 映射为
     *  {@code langfuse.observation.input/output} = Langfuse trace 列表的 input/output）。 */
    private static final String KEY_TRACE_INPUT = "rag.trace.input";
    private static final String KEY_TRACE_OUTPUT = "rag.trace.output";

    /** 写<b>trace 级</b> input（{@code rag.trace.input}）。
     *  <p>供 eval 等<b>非对话</b> root span（{@link RagTelemetry#startRoot}）显式标记 trace 级 IO：
     *  让 Langfuse trace 列表的 input/output 稳定显示，不再依赖子 LLM span 的 {@code gen_ai.*}
     *  （ReAct 多轮 / 流式下 completion 常捕不全 → output 丢失）。对话链用 {@link ConversationTrace}，
     *  不由此方法。截断口径同为 {@link #MAX_SPAN_IO}。 */
    public StepSpan traceInput(Object value) {
        setTraceIo(KEY_TRACE_INPUT, value);
        return this;
    }

    /** 写<b>trace 级</b> output（{@code rag.trace.output}）。语义同 {@link #traceInput}。 */
    public StepSpan traceOutput(Object value) {
        setTraceIo(KEY_TRACE_OUTPUT, value);
        return this;
    }

    /** trace 级 IO 落 attribute：String 原样 + MAX_SPAN_IO 截断（与 {@link ConversationTrace} 一致，
     *  不走 Summarizer 摘要——trace 级 IO 要原文，便于 Langfuse 直接看用户问题 / 检索结果）。 */
    private void setTraceIo(String key, Object value) {
        if (value == null) {
            return;
        }
        try {
            String s = value.toString();
            span.setAttribute(key, s.length() > MAX_SPAN_IO ? s.substring(0, MAX_SPAN_IO) : s);
        } catch (Exception ignored) {
            // span 已结束或设置失败，忽略
        }
    }

    /** 计算 output 摘要：rawOutput 时原样返回（完整记录），否则走 Summarizer（200字摘要）。 */
    private Object summarizeOutput() {
        return rawOutput ? outputData : Summarizer.summarize(outputData);
    }

    public StepSpan error(Throwable t) {
        if (observation != null) {
            observation.error(t);
        } else {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR);
        }
        return this;
    }

    /** 同步关闭：发起线程关 scope + stop + 还原 MDC。幂等。 */
    @Override
    public void close() {
        if (completed) {
            return;
        }
        completed = true;
        try {
            Object outSummary = summarizeOutput();
            StructuredLog.emit("step.output", name, spanId, outSummary, System.currentTimeMillis() - startMs);
            setSpanIo("output", outSummary);
        } finally {
            if (observation != null) {
                try {
                    scope.close();
                } catch (Exception ignored) {
                }
                try {
                    observation.stop();
                } catch (Exception ignored) {
                }
            } else {
                try {
                    otelScope.close();
                } catch (Exception ignored) {
                }
                try {
                    span.end();
                } catch (Exception ignored) {
                }
            }
            MDC.remove("rag_step");
            MDC.remove("step_id");
        }
    }

    /** 异步关闭（流式 doFinally）：记 output + 落 output span attribute + stop；MDC/trace 由 Reactor 自动
     *  传播恢复，scope 不关。幂等。doFinally 回调线程的当前 span 由 {@code Hooks.enableAutomaticContextPropagation}
     *  恢复为流式 step span，故 setSpanIo 能正确落到本步骤 span（与 {@link #close()} 同理）。 */
    public void finish() {
        if (completed) {
            return;
        }
        completed = true;
        try {
            Object outSummary = summarizeOutput();
            StructuredLog.emit("step.output", name, spanId, outSummary, System.currentTimeMillis() - startMs);
            setSpanIo("output", outSummary);
        } finally {
            if (observation != null) {
                try {
                    observation.stop();
                } catch (Exception ignored) {
                }
            } else {
                try {
                    span.end();
                } catch (Exception ignored) {
                }
            }
            MDC.remove("rag_step");
            MDC.remove("step_id");
        }
    }

    public void closeScope() {
        if (scope != null) {
            try {
                scope.close();
            } catch (Exception ignored) {
            }
        } else if (otelScope != null) {
            try {
                otelScope.close();
            } catch (Exception ignored) {
            }
        }
        MDC.remove("rag_step");
        MDC.remove("step_id");
    }
}
