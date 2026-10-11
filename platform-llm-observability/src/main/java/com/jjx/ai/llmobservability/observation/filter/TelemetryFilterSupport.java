package com.jjx.ai.llmobservability.observation.filter;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;

/**
 * 过滤器链的静态桥（供 {@code TelemetryStructuredLog} 等静态门面在 Spring 容器外复用同一链路）。
 *
 * <p><b>所属维度</b>：转（过滤器链 SPI 的容器桥）。启动期由自动装配调用 {@link #install} 安装一次；
 * 未安装或开关关闭时 {@link #pass} 恒为 {@code true}，不改变既有日志行为。</p>
 */
public final class TelemetryFilterSupport {

    private static volatile TelemetryFilterChain chain;
    private static volatile boolean enabled = true;

    private TelemetryFilterSupport() {
    }

    /** 安装静态桥（启动期调用一次；null 视为未安装）。 */
    public static void install(TelemetryFilterChain filterChain, boolean filterEnabled) {
        chain = filterChain;
        enabled = filterEnabled;
    }

    /**
     * 执行过滤器链（若已安装且开启）。
     *
     * @param event 待过滤的观测事件
     * @return {@code true} = 放行；{@code false} = 被过滤器丢弃
     */
    public static boolean pass(TelemetryEvent event) {
        TelemetryFilterChain current = chain;
        if (current == null || !enabled) {
            return true;
        }
        return current.apply(event);
    }
}
