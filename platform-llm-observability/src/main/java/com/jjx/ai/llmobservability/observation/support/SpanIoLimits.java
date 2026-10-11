package com.jjx.ai.llmobservability.observation.support;

/**
 * span / trace attribute 的字符长度上限（全局唯一来源；attribute key 统一收口在 {@link AttributeKeys}）。
 *
 * <p><b>所属维度</b>：转（processor 层常量，被 {@code SpanIoLimitProcessor} / llm 集成 / TelemetryTemplate 引用）。</p>
 *
 * <p><b>职责</b>：集中"单字段防膨胀上限"，消除散落硬编码。
 * 上限默认 20000，可经 {@code telemetry.limits.max-span-io} 覆盖（由 TelemetryAutoConfiguration 在启动期调用
 * {@link #configure(int)}，启动后不再变更）。</p>
 */
public final class SpanIoLimits {

    private SpanIoLimits() {
    }

    private static volatile int maxSpanIo = 20000;
    private static volatile boolean truncateEnabled = true;

    /** span/trace 单字段安全字符上限（防字段膨胀）。 */
    public static int maxSpanIo() {
        return maxSpanIo;
    }

    public static boolean isTruncateEnabled() {
        return truncateEnabled;
    }

    /** 覆盖全局上限与截断开关（启动期配置一次；非正值忽略）。 */
    public static void configure(int newMaxSpanIo, boolean newTruncateEnabled) {
        if (newMaxSpanIo > 0) {
            maxSpanIo = newMaxSpanIo;
        }
        truncateEnabled = newTruncateEnabled;
    }
}
