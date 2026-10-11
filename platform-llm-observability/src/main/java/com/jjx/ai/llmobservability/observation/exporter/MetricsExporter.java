package com.jjx.ai.llmobservability.observation.exporter;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.span.SpanWriter;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.springframework.core.annotation.Order;

/**
 * Metrics 支柱 exporter（发·出口 C）。
 *
 * <p><b>所属维度</b>：发（{@link ObservationExporter}，@Order(30)，MeterRegistry 缺失时 no-op）。</p>
 *
 * <p><b>职责</b>：STEP_OUTPUT 时按步骤打耗时 Timer（{@code {ns}.step.duration}，tag=步骤名低基数）。
 * 指标名走命名空间前缀——OTel GenAI 语义约定只标准化了 LLM 客户端指标
 * （{@code gen_ai.client.operation.duration} 等，由 Spring AI 原生观察输出，本类不重复）；
 * "流水线步骤耗时"是框架自有概念，无标准名，按 {@link AttributeKeys#stepDurationMetric()} 命名。</p>
 *
 * <p><b>不做什么</b>：不写 span attribute / 日志（出口 A/B 的职责）；tag 只放低基数步骤名。</p>
 */
@Order(30)
public class MetricsExporter implements ObservationExporter {

    private final MeterRegistry meterRegistry;

    /** @param meterRegistry 可为 null（宿主无 micrometer-core 时 no-op） */
    public MetricsExporter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void export(TelemetryEvent event, SpanWriter target) {
        if (meterRegistry == null || event.getType() != TelemetryEvent.EventType.STEP_OUTPUT
                || event.getDurationMs() == null || event.getName() == null) {
            return;
        }
        try {
            meterRegistry.timer(AttributeKeys.stepDurationMetric(), AttributeKeys.step(), event.getName())
                    .record(event.getDurationMs(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // 指标绝不影响观测事件流
        }
    }
}
