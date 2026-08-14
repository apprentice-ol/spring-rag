package com.nageoffer.ai.obs.exporter;

import com.nageoffer.ai.obs.backend.SpanWriter;
import com.nageoffer.ai.obs.event.TraceEvent;

/**
 * Metrics 支柱预留 exporter（当前 no-op）。
 *
 * <p><b>所属维度</b>：发（{@link TraceExporter} 预留扩展位）。</p>
 *
 * <p><b>职责（规划）</b>：STEP_OUTPUT 时打 Micrometer 指标——Timer（按 step name 耗时分布）、
 * Counter（次数），补齐 metrics 支柱。启用时：实现 export + 注入 MeterRegistry + 加 @Component。</p>
 *
 * <p><b>当前状态</b>：no-op，不注册为 bean。本类的存在让 metrics 扩展点显式可见。</p>
 */
public class MetricsExporter implements TraceExporter {

    @Override
    public void export(TraceEvent event, SpanWriter target) {
        // TODO(后续任务): Micrometer Timer/Counter 打点。
        //   if (event.getType() == EventType.STEP_OUTPUT && event.getDurationMs() != null) {
        //       meterRegistry.timer("obs.step.duration", "step", event.getName())
        //               .record(event.getDurationMs(), TimeUnit.MILLISECONDS);
        //   }
    }
}
