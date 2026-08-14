package com.nageoffer.ai.obs.observation.support;

/**
 * span / trace attribute 的字符长度上限与 trace 级 IO attribute key 的全局唯一来源。
 *
 * <p><b>所属维度</b>：转（processor 层常量，被 {@code SpanIoLimitProcessor} / llm 集成 / ObsTemplate 引用）。</p>
 *
 * <p><b>职责</b>：集中"单字段防膨胀上限"和 trace IO attribute key，消除散落硬编码。
 * 上限默认 20000，可经 {@code obs.limits.max-span-io} 覆盖（由 ObsAutoConfiguration 在启动期调用
 * {@link #configure(int)}，启动后不再变更）。</p>
 *
 * <p><b>关于 {@code rag.trace.*} key</b>：保留此 key 兼容 OTel Collector 的 transform processor
 * （据此映射 langfuse.observation.*）。app 直连 Langfuse 时可用 LangfuseTransformProcessor 转换。</p>
 */
public final class SpanIoLimits {

    private SpanIoLimits() {
    }

    private static volatile int maxSpanIo = 20000;

    /** span/trace 单字段安全字符上限（防字段膨胀）。 */
    public static int maxSpanIo() {
        return maxSpanIo;
    }

    /** 覆盖全局上限（启动期配置一次；非正值忽略）。 */
    public static void configure(int newMaxSpanIo) {
        if (newMaxSpanIo > 0) {
            maxSpanIo = newMaxSpanIo;
        }
    }

    /** trace 级 input attribute key（OTel Collector 映射为 langfuse.observation.input）。保留兼容。 */
    public static final String KEY_TRACE_INPUT = "rag.trace.input";

    /** trace 级 output attribute key（OTel Collector 映射为 langfuse.observation.output）。保留兼容。 */
    public static final String KEY_TRACE_OUTPUT = "rag.trace.output";
}
