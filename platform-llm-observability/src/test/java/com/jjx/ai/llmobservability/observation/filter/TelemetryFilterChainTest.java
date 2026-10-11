package com.jjx.ai.llmobservability.observation.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

class TelemetryFilterChainTest {

    @Test
    void runsFiltersByOrderAndMutatesData() {
        TelemetryFilterChain chain = new TelemetryFilterChain(List.of(new LaterFilter(), new EarlierFilter()));
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.STEP_INPUT, "rag.step", "span-1",
                Map.of("password", "secret", "keep", "v"));

        assertTrue(chain.apply(event));

        Map<?, ?> data = (Map<?, ?>) event.getData();
        assertEquals("[REDACTED]", data.get("password"));
        assertEquals("v", data.get("keep"));
        assertEquals(Boolean.TRUE, data.get("extra"));
    }

    @Test
    void filterNotProceedingDropsEventAndSkipsTerminal() {
        List<String> order = new ArrayList<>();
        TelemetryFilterChain chain = new TelemetryFilterChain(
                List.of((event, next) -> { /* 不调用 chain.doFilter = 丢弃 */ }));
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        boolean forwarded = chain.apply(event, e -> order.add("terminal"));

        assertFalse(forwarded);
        assertTrue(order.isEmpty());
    }

    @Test
    void postCodeRunsAfterTerminal() {
        List<String> order = new ArrayList<>();
        TelemetryFilterChain chain = new TelemetryFilterChain(
                List.of((event, next) -> {
                    order.add("pre");
                    next.doFilter(event);
                    order.add("post");
                }));
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        assertTrue(chain.apply(event, e -> order.add("terminal")));
        assertEquals(List.of("pre", "terminal", "post"), order);
    }

    @Test
    void emptyChainAlwaysForwards() {
        TelemetryFilterChain chain = new TelemetryFilterChain(List.of());
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM, "llm.request", null, "payload");

        assertTrue(chain.apply(event));
    }

    @Order(20)
    static class LaterFilter implements TelemetryFilter {

        @Override
        public void doFilter(TelemetryEvent event, TelemetryFilterChain chain) {
            Map<?, ?> data = (Map<?, ?>) event.getData();
            event.setData(Map.of(
                    "password", data.get("password"),
                    "keep", data.get("keep"),
                    "extra", true));
            chain.doFilter(event);
        }
    }

    @Order(10)
    static class EarlierFilter implements TelemetryFilter {

        @Override
        public void doFilter(TelemetryEvent event, TelemetryFilterChain chain) {
            Map<?, ?> data = (Map<?, ?>) event.getData();
            event.setData(Map.of(
                    "password", "[REDACTED]",
                    "keep", data.get("keep")));
            chain.doFilter(event);
        }
    }
}
