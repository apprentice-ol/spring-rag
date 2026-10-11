package com.jjx.ai.llmobservability.observation;

import com.jjx.ai.llmobservability.observation.span.SpanWriter;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilterChain;
import com.jjx.ai.llmobservability.observation.exporter.ObservationExporter;
import com.jjx.ai.llmobservability.observation.processor.ObservationProcessor;
import java.util.List;
import org.springframework.core.annotation.OrderUtils;

/**
 * 观测 pipeline 编排：事件串行流过 processor 链（转），fan-out 给 exporter 链（发）。
 *
 * <p><b>所属维度</b>：转+发（pipeline 编排核心，对应 otel-collector 的 service.pipelines）。</p>
 *
 * <p><b>职责</b>：{@link #emit} 是全部观测事件的唯一通道——Source（切面/门面）产 {@link TelemetryEvent} 调它，
 * 先串行过 processor（任一返回 null 即过滤终止），再 fan-out 给全部 exporter。</p>
 *
 * <p><b>编排结构</b>：</p>
 * <pre>
 * TelemetryEvent ──> [processor₁ → processor₂ → ...]（串行转，可过滤）
 *                     │ null → 丢弃
 *                     ▼
 *              [exporter₁, exporter₂, ...]（fan-out 发）
 * </pre>
 *
 * <p><b>协作</b>：由 {@code TelemetryAutoConfiguration} 装配（注入 Spring 收集的全部
 * {@link ObservationProcessor} / {@link ObservationExporter} bean，按 @Order 排序）；被 {@code TelemetrySpan} /
 * {@code ConversationContext} / {@code TelemetryTemplate.emit} 调用。</p>
 *
 * <p><b>扩展模型</b>：新 processor/exporter = 实现接口 + 注册为 bean（@Component 或 @Bean 均可），
 * 本类与所有 Source 零改动——开闭原则。
 * 处理器链内置顺序：摘要（10）→ 截断（20）→ [用户扩展 30+]；exporter：span（10）→ 日志（20）→ metrics（30）；过滤器链（TelemetryFilter）包在整条 emit 之外，先于 processor 执行。</p>
 *
 * <p><b>性能契约</b>：{@link #emit} 在业务线程<b>同步</b>执行整条链，所有 processor/exporter
 * 必须无阻塞（内存写/本地日志），任何网络 IO 一律走后端（OTLP exporter / collector）。</p>
 */
public class ObservationPipeline {

    private final List<ObservationProcessor> processors;
    private final List<ObservationExporter> exporters;
    private final TelemetryFilterChain filterChain;
    private final boolean filterEnabled;

    public ObservationPipeline(List<ObservationProcessor> processors, List<ObservationExporter> exporters) {
        this(processors, exporters, null, true);
    }

    public ObservationPipeline(List<ObservationProcessor> processors, List<ObservationExporter> exporters,
                               TelemetryFilterChain filterChain, boolean filterEnabled) {
        this.processors = processors.stream()
                .sorted((a, b) -> Integer.compare(
                        OrderUtils.getOrder(a.getClass(), Integer.MAX_VALUE),
                        OrderUtils.getOrder(b.getClass(), Integer.MAX_VALUE)))
                .toList();
        this.exporters = exporters.stream()
                .sorted((a, b) -> Integer.compare(
                        OrderUtils.getOrder(a.getClass(), Integer.MAX_VALUE),
                        OrderUtils.getOrder(b.getClass(), Integer.MAX_VALUE)))
                .toList();
        this.filterChain = filterChain;
        this.filterEnabled = filterEnabled;
    }

    /**
     * 事件的唯一通道：processor 链转（任一 null 即过滤）→ exporter 链发。
     *
     * @param event  原始事件（data 未加工）
     * @param target 本次事件的写 span 目标（backend / 根 span writer / ambient）
     */
    public void emit(TelemetryEvent event, SpanWriter target) {
        if (filterChain != null && filterEnabled) {
            // 过滤器链包住整条下游：chain.doFilter 之前是前置，之后是后置；不调用 = 丢弃
            if (!filterChain.apply(event, e -> emitInternal(e, target))) {
                return;
            }
            return;
        }
        emitInternal(event, target);
    }

    private void emitInternal(TelemetryEvent event, SpanWriter target) {
        for (ObservationProcessor p : processors) {
            try {
                event = p.process(event);
                if (event == null) {
                    return;  // 被过滤，不流向 exporter
                }
            } catch (Throwable ignored) {
                // 观测 processor 永不阻断业务
            }
        }
        for (ObservationExporter e : exporters) {
            try {
                e.export(event, target);
            } catch (Throwable ignored) {
                // 观测 exporter 永不阻断业务
            }
        }
    }
}
