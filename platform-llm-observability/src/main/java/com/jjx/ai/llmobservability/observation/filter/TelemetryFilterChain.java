package com.jjx.ai.llmobservability.observation.filter;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.core.annotation.OrderUtils;

/**
 * 观测事件过滤器链——按 {@code @Order} / {@code Ordered} 顺序执行全部 {@link TelemetryFilter}。
 *
 * <p><b>所属维度</b>：转（过滤器链 SPI 的执行器，由 {@code ObservationPipeline} 与外部代码共同使用）。</p>
 *
 * <p><b>模型</b>：借鉴 Spring Web 的 {@code FilterChain}。每个过滤器在
 * {@code chain.doFilter(event)} 之前做前置处理、之后做后置处理；不调用则整条链视为“丢弃事件”
 * （{@link #apply(TelemetryEvent, Consumer)} 返回 {@code false}）。</p>
 *
 * <p><b>异常策略</b>：与观察管线一致，过滤器异常不阻断业务——未放行到下游的异常会跳过当前环继续，
 * 已放行后抛出的异常仅丢弃后置代码，不重复执行下游。</p>
 *
 * <p><b>线程安全</b>：过滤器列表在构造期排序后只读；每次 {@code apply} 使用独立执行上下文，链 bean 可多线程复用。</p>
 */
public class TelemetryFilterChain {

    private final List<TelemetryFilter> filters;
    private final int index;
    private final ChainContext context;

    public TelemetryFilterChain(List<TelemetryFilter> filters) {
        this(sort(filters), 0, null);
    }

    private TelemetryFilterChain(List<TelemetryFilter> filters, int index, ChainContext context) {
        this.filters = filters;
        this.index = index;
        this.context = context;
    }

    /**
     * 入口：从第一环开始执行整条链，下游终端为 no-op（仅执行过滤器，不导出）。
     *
     * @param event 待处理的观测事件
     * @return {@code true} = 全部过滤器放行；{@code false} = 被某个过滤器丢弃
     */
    public boolean apply(TelemetryEvent event) {
        return apply(event, e -> { });
    }

    /**
     * 入口：从第一环开始执行整条链，全部放行后调用 {@code terminal}（如观察管线的 processor/exporter 链）。
     *
     * @param event    待处理的观测事件
     * @param terminal 全部过滤器放行后的下游动作
     * @return {@code true} = 全部过滤器放行；{@code false} = 被某个过滤器丢弃
     */
    public boolean apply(TelemetryEvent event, Consumer<TelemetryEvent> terminal) {
        ChainContext ctx = new ChainContext(terminal);
        invoke(event, ctx);
        return ctx.forwarded.get();
    }

    /**
     * 过滤器内调用：放行到下一环；全部放行后执行下游终端。
     *
     * @param event 当前事件
     */
    public void doFilter(TelemetryEvent event) {
        if (context == null) {
            apply(event);
            return;
        }
        invoke(event, context);
    }

    private void invoke(TelemetryEvent event, ChainContext ctx) {
        if (index >= filters.size()) {
            if (ctx.forwarded.compareAndSet(false, true)) {
                ctx.completed.set(true);
                ctx.terminal.accept(event);
            }
            return;
        }
        try {
            filters.get(index).doFilter(event, new TelemetryFilterChain(filters, index + 1, ctx));
        } catch (Throwable ignored) {
            if (!ctx.completed.get()) {
                new TelemetryFilterChain(filters, index + 1, ctx).doFilter(event);
            }
        }
    }

    private static List<TelemetryFilter> sort(List<TelemetryFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        return filters.stream()
                .sorted(Comparator.comparingInt(
                        f -> OrderUtils.getOrder(f.getClass(), Integer.MAX_VALUE)))
                .toList();
    }

    /** 单次执行的共享状态：是否放行、下游是否已执行、下游终端。 */
    private static final class ChainContext {

        private final AtomicBoolean forwarded = new AtomicBoolean(false);
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final Consumer<TelemetryEvent> terminal;

        private ChainContext(Consumer<TelemetryEvent> terminal) {
            this.terminal = terminal;
        }
    }
}
