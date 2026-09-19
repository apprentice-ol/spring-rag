package com.agentframework.crosscutting.trace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次操作的耗时记录。
 *
 * <p>Span 是可变的：开始时创建，结束时补上耗时、状态与错误信息，随后由 {@code Tracer} 导出。</p>
 */
public final class Span {

    private final String id;
    private final String traceId;
    private final String parentId;
    private final String name;
    private final SpanKind kind;
    private final Instant startTime;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<SpanEvent> events = new ArrayList<>();

    private volatile Instant endTime;
    private volatile SpanStatus status = SpanStatus.UNSET;
    private volatile String error;

    /**
     * @param traceId  所属链路 id
     * @param parentId 父 span id，根 span 为 null
     * @param name     span 名称
     * @param kind     span 类型
     */
    public Span(String traceId, String parentId, String name, SpanKind kind) {
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.traceId = traceId;
        this.parentId = parentId;
        this.name = name == null ? "span" : name;
        this.kind = kind == null ? SpanKind.INTERNAL : kind;
        this.startTime = Instant.now();
    }

    /** @return span id */
    public String id() {
        return id;
    }

    /** @return 所属链路 id */
    public String traceId() {
        return traceId;
    }

    /** @return 父 span id，根 span 为 null */
    public String parentId() {
        return parentId;
    }

    /** @return span 名称 */
    public String name() {
        return name;
    }

    /** @return span 类型 */
    public SpanKind kind() {
        return kind;
    }

    /** @return 开始时间 */
    public Instant startTime() {
        return startTime;
    }

    /** @return 结束时间，未结束返回 null */
    public Instant endTime() {
        return endTime;
    }

    /** @return 状态 */
    public SpanStatus status() {
        return status;
    }

    /** @return 错误信息，未失败返回 null */
    public String error() {
        return error;
    }

    /** @return 属性只读视图 */
    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }

    /** @return 事件只读视图 */
    public List<SpanEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /**
     * 写入属性。
     *
     * @param key   属性名
     * @param value 属性值
     */
    public void setAttribute(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
    }

    /**
     * 追加事件。
     *
     * @param event 事件
     */
    public void addEvent(SpanEvent event) {
        if (event != null) {
            synchronized (events) {
                events.add(event);
            }
        }
    }

    /**
     * 以成功状态结束 span。
     */
    public void end() {
        this.endTime = Instant.now();
        this.status = SpanStatus.OK;
    }

    /**
     * 以失败状态结束 span。
     *
     * @param cause 失败原因
     */
    public void endWithError(Throwable cause) {
        this.endTime = Instant.now();
        this.status = SpanStatus.ERROR;
        this.error = cause == null ? "unknown error" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** @return 耗时；未结束时返回从开始到现在的时长 */
    public Duration duration() {
        Instant end = endTime;
        return Duration.between(startTime, end == null ? Instant.now() : end);
    }

    /** @return 是否已结束 */
    public boolean finished() {
        return endTime != null;
    }

    /** @return 便于日志输出的摘要 */
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", traceId);
        summary.put("spanId", id);
        if (parentId != null) {
            summary.put("parentId", parentId);
        }
        summary.put("name", name);
        summary.put("kind", kind.name());
        summary.put("status", status.name());
        summary.put("durationMs", duration().toMillis());
        summary.putAll(attributes);
        if (error != null) {
            summary.put("error", error);
        }
        return summary;
    }
}
