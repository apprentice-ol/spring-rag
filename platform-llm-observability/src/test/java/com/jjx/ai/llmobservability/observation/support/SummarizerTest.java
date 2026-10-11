package com.jjx.ai.llmobservability.observation.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间类型摘要回归：JDK 17+ 模块系统下，默认 Gson 反射访问 {@code java.time.Instant#seconds}
 * 等内部字段会被拒绝，摘要层只能把整个对象降级成 {@code {type,error}} 占位——业务对象带一个
 * 时间字段，整个输入输出就变空白（2026-10-11 真实 trace 上 node:、workflow:、tool: 三类 span 全中招）。
 *
 * <p>修复后（{@link ObservationJson} 注册 java.time 序列化器）时间字段以 ISO-8601 文本保留。
 * 本测试锁住该行为：再有人把摘要层换回裸 Gson / 删掉注册，这里立刻红。</p>
 */
class SummarizerTest {

    record SlotsLike(String question, Instant updatedAt) {
    }

    record ToolResultLike(String tool, Duration latency, Map<String, Object> data) {
    }

    @Test
    void instantFieldIsSerializedAsIsoText() {
        Object summarized = Summarizer.summarize(
                new SlotsLike("token是什么", Instant.parse("2026-10-11T01:00:00Z")));

        String json = ObservationJson.toJson(summarized);
        assertTrue(json.contains("token是什么"), "业务字段必须保留: " + json);
        assertTrue(json.contains("2026-10-11T01:00:00Z"), "Instant 必须落 ISO-8601 文本: " + json);
        assertFalse(json.contains("serialize failed"), "不允许再出现序列化失败占位: " + json);
    }

    @Test
    void durationFieldIsSerializedAsIsoText() {
        Object summarized = Summarizer.summarize(
                new ToolResultLike("retrieve_knowledge", Duration.ofMillis(1500), Map.of("k", "v")));

        String json = ObservationJson.toJson(summarized);
        assertTrue(json.contains("PT1.5S"), "Duration 必须落 ISO-8601 文本: " + json);
        assertFalse(json.contains("serialize failed"), "不允许再出现序列化失败占位: " + json);
    }

    @Test
    void collectionKeepsSizeAndPreview() {
        Object summarized = Summarizer.summarize(List.of("a", "b", "c", "d", "e"));

        String json = ObservationJson.toJson(summarized);
        assertTrue(json.contains("\"size\":5"), "集合摘要保留总数: " + json);
        assertFalse(json.contains("\"d\""), "超出 preview 上限的元素不进摘要: " + json);
    }

    @Test
    void oversizedJsonDegradesToValidTruncationMarker() {
        String big = "x".repeat(5000);
        String json = Summarizer.toJsonTruncated(Map.of("data", big), 100);

        assertTrue(json.contains("_truncated"), "超长降级标记: " + json);
        assertDoesNotThrow(() -> ObservationJson.gson().fromJson(json, Object.class),
                "降级结果必须是合法 JSON（不能是半截字符串）: " + json);
    }
}
