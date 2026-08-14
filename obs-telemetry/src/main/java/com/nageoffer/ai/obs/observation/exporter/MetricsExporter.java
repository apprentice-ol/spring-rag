package com.nageoffer.ai.obs.observation.exporter;

import com.nageoffer.ai.obs.observation.span.SpanWriter;
import com.nageoffer.ai.obs.observation.event.ObsEvent;

/**
 * Metrics 支柱预留 exporter（当前 no-op）。
 *
 * <p><b>所属维度</b>：发（{@link ObservationExporter} 预留扩展位）。</p>
 *
 * <p><b>职责（规划）</b>：STEP_OUTPUT 时打 Micrometer 指标——Timer（按 step name 耗时分布）、
 * Counter（次数），补齐 metrics 支柱。启用时：实现 export + 注入 MeterRegistry + 加 @Component。</p>
 *
 * <p><b>当前状态</b>：no-op，不注册为 bean。本类的存在让 metrics 扩展点显式可见。</p>
 */
public class MetricsExporter implements ObservationExporter {

    @Override
    public void export(ObsEvent event, SpanWriter target) {
        // TODO(后续任务): Micrometer Timer/Counter 打点。
        //   if (event.getType() == EventType.STEP_OUTPUT && event.getDurationMs() != null) {
        //       meterRegistry.timer("obs.step.duration", "step", event.getName())
        //               .record(event.getDurationMs(), TimeUnit.MILLISECONDS);
        //   }
    }
}
