package com.jjx.ai.llmobservability.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.exporter.ObservationExporter;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilterChain;
import com.jjx.ai.llmobservability.observation.span.SpanWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationPipelineFilterTest {

    private static final SpanWriter NOOP_WRITER = new SpanWriter() {
        @Override
        public void setAttribute(String key, String value) {
        }

        @Override
        public void setTag(String key, String value) {
        }

        @Override
        public void recordError(Throwable t) {
        }
    };

    @Test
    void droppedEventNeverReachesExporter() {
        TelemetryFilterChain chain = new TelemetryFilterChain(
                List.of((event, next) -> { }));
        ObservationPipeline pipeline = new ObservationPipeline(
                List.of(), List.of(new RecordingExporter()), chain, true);
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        pipeline.emit(event, NOOP_WRITER);
    }

    @Test
    void preFilterCanModifyDataBeforeExporter() {
        TelemetryFilterChain chain = new TelemetryFilterChain(
                List.of((event, next) -> {
                    event.setData("[REDACTED]");
                    next.doFilter(event);
                }));
        RecordingExporter exporter = new RecordingExporter();
        ObservationPipeline pipeline = new ObservationPipeline(
                List.of(), List.of(exporter), chain, true);
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        pipeline.emit(event, NOOP_WRITER);

        assertEquals("[REDACTED]", exporter.seenData);
    }

    @Test
    void postFilterRunsAfterExporter() {
        List<String> order = new ArrayList<>();
        TelemetryFilterChain chain = new TelemetryFilterChain(
                List.of((event, next) -> {
                    order.add("pre");
                    next.doFilter(event);
                    order.add("post");
                }));
        ObservationPipeline pipeline = new ObservationPipeline(
                List.of(), List.of(new RecordingExporter(order)), chain, true);
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        pipeline.emit(event, NOOP_WRITER);

        assertEquals(List.of("pre", "export", "post"), order);
    }

    @Test
    void disabledFilterChainPassesThrough() {
        TelemetryFilterChain chain = new TelemetryFilterChain(
                List.of((event, next) -> { }));
        RecordingExporter exporter = new RecordingExporter();
        ObservationPipeline pipeline = new ObservationPipeline(
                List.of(), List.of(exporter), chain, false);
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        pipeline.emit(event, NOOP_WRITER);

        assertEquals("payload", exporter.seenData);
    }

    private static final class RecordingExporter implements ObservationExporter {

        private final List<String> order;
        private Object seenData;

        private RecordingExporter() {
            this(null);
        }

        private RecordingExporter(List<String> order) {
            this.order = order;
        }

        @Override
        public void export(TelemetryEvent event, SpanWriter target) {
            seenData = event.getData();
            if (order != null) {
                order.add("export");
            }
        }
    }
}
