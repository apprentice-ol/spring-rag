package com.jjx.ai.llmobservability.observation.filter;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;

/**
 * 观测事件过滤器——外部扩展点（借鉴 Spring 的 Filter / FilterChain 模型）。
 *
 * <p><b>所属维度</b>：telemetry 扩展点（通用事件过滤器链 SPI）。</p>
 *
 * <p><b>职责</b>：包住“观察事件从处理器链到 exporter 链”的整条下游链路，提供前置/后置两个时机：</p>
 * <ul>
 *   <li><b>前置</b>：{@code chain.doFilter(event)} 之前的代码，可拦截、修改事件数据
 *       （如脱敏 {@link TelemetryEvent#setData}），在摘要/截断/exporter 之前执行；</li>
 *   <li><b>后置</b>：{@code chain.doFilter(event)} 之后的代码，在下游处理器与 exporter
 *       全部执行完成后执行（如补指标、审计、统计被丢弃事件）；</li>
 *   <li><b>丢弃</b>：不调用 {@code chain.doFilter(event)}，该事件不会进入任何 processor / exporter。</li>
 * </ul>
 *
 * <p><b>如何生效</b>：实现本接口并注册为 Spring bean（{@code @Component} 或 {@code @Bean}），
 * 自动装配会收集全部实现，按 {@code @Order} / {@code Ordered} 排序后由
 * {@code ObservationPipeline} 统一执行。过滤器链本身也可以作为 bean 注入业务代码手动执行。</p>
 *
 * <pre>{@code
 * @Component
 * @Order(30)
 * public class PasswordRedactor implements TelemetryFilter {
 *     @Override
 *     public void doFilter(TelemetryEvent event, TelemetryFilterChain chain) {
 *         // 前置：拦截 / 修改
 *         if (event.getData() instanceof Map<?, ?> map && map.containsKey("password")) {
 *             event.setData("[REDACTED]");
 *         }
 *         chain.doFilter(event);      // 放行到下游；不调用 = 丢弃
 *         // 后置：下游处理器与 exporter 全部完成后执行
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface TelemetryFilter {

    /**
     * 处理一个观测事件。
     *
     * @param event 当前事件（可 {@code setData} 就地修改）
     * @param chain 过滤器链游标，调用 {@code doFilter(event)} 放行到下游；不调用则丢弃事件
     */
    void doFilter(TelemetryEvent event, TelemetryFilterChain chain);
}
