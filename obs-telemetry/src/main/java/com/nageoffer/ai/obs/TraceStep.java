package com.nageoffer.ai.obs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个观测步骤方法，由 {@code trigger.TraceStepAspect} 自动埋点：开 span、摘要输入输出、
 * 记耗时与异常、按方法返回类型自动关 span（同步 close / 流式 finish），方法体无需任何改动。
 *
 * <p>{@link #value()} 是 span 名。若目标 bean 暴露无参 {@code getName()}，实际 span 名 = {@code value + "." + getName()}，
 * 用于把 {@code @TraceStep("channel")} 展开成 {@code channel.vector} 等。</p>
 *
 * <p>{@link #kind()} 指定父子关系（parentage，与 OTel SpanKind 无关）：
 * <ul>
 *   <li>{@code STEP}（默认）：挂当前 ambient 父 span 下。</li>
 *   <li>{@code ROOT}：强制开一条新的 trace 根 span（无父），用于后台任务或"已 in-trace 需脱钩"。</li>
 * </ul></p>
 *
 * <p>{@link #captureOutput()}：流式方法（返回 {@code Flux}/{@code Mono}/{@code SseEmitter}）为 true 时，
 * 把完整输出原样记为 span output，否则不捕获（默认）。同步方法忽略。</p>
 *
 * <p>生命周期由返回类型自动分派：普通对象→同步 close；{@code Flux}/{@code Mono}→{@code doFinally} finish；
 * {@code SseEmitter}/{@code ResponseBodyEmitter}→完成回调 finish。</p>
 *
 * <p><b>限制</b>：仅对 Spring 代理的 bean 方法生效（同类内部调用 / 静态方法 / 私有方法切不到），这类盲区用手动 {@code Telemetry}。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TraceStep {

    /** span 名前缀，如 {@code "query.rewrite"} / {@code "channel"} / {@code "postproc"}。 */
    String value();

    /** 父子关系：STEP（挂当前父，默认）/ ROOT（强制无父新 trace）。 */
    Kind kind() default Kind.STEP;

    /** 流式方法是否把完整输出原样记为 span output。同步方法忽略。 */
    boolean captureOutput() default false;

    enum Kind {
        /** 挂当前 ambient 父 span（默认）。 */
        STEP,
        /** 强制开新 trace 根（无父）。 */
        ROOT
    }
}
