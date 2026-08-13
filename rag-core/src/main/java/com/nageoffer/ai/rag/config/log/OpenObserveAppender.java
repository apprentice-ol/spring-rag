package com.nageoffer.ai.rag.config.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import cn.hutool.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenObserve 日志 Appender（Logback 版）
 *
 * <p>将应用日志以 JSON 形式批量、异步推送至 OpenObserve 的 {@code _json} ingestion 端点。
 * 自动携带 MDC 中的 {@code traceId}/{@code spanId}（由 Micrometer OTel 注入），
 * 实现链路追踪与日志的关联。
 *
 * <p>特性：
 * <ul>
 *   <li>基于 JDK 内置 HttpClient，零额外依赖</li>
 *   <li>有界队列缓冲 + 定时批量推送，不阻塞业务线程</li>
 *   <li>队列满时丢弃并计数，避免反压拖垮应用</li>
 *   <li>应用端微秒级时间戳</li>
 * </ul>
 *
 * <p>logback-spring.xml 配置示例：
 * <pre>{@code
 * <appender name="OPENOBSERVE" class="com.nageoffer.ai.rag.config.log.OpenObserveAppender">
 *     <url>http://localhost:5080/api/default</url>
 *     <stream>springai-rag_logs</stream>
 *     <service>springai-rag</service>
 *     <username>admin@openobserve.io</username>
 *     <password>${OO_PASSWORD:-OpenObserve@2026}</password>
 *     <batchSize>200</batchSize>
 *     <flushIntervalMillis>5000</flushIntervalMillis>
 *     <queueCapacity>10000</queueCapacity>
 * </appender>
 * }</pre>
 *
 * @see <a href="https://openobserve.ai/docs/api/logs/ingestion/">OpenObserve Logs Ingestion API</a>
 */
public class OpenObserveAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final int DEFAULT_BATCH_SIZE = 200;
    private static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 5000L;
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    // ---------- logback-spring.xml 注入的属性 ----------
    private String url;
    private String stream;
    private String service;
    private String username;
    private String password;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private long flushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

    // ---------- 运行时状态 ----------
    private String ingestionUrl;
    private String authHeader;
    private LinkedBlockingQueue<String> queue;
    private final AtomicLong droppedCount = new AtomicLong();
    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    @Override
    public void start() {
        if (url == null || url.isEmpty() || stream == null || stream.isEmpty()) {
            addError("OpenObserve Appender 缺少 url/stream 配置，不启用");
            return;
        }
        final String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.ingestionUrl = base + "/" + stream + "/_json";

        if (username != null && !username.isEmpty() && password != null) {
            final String raw = username + ":" + password;
            this.authHeader = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        final ThreadFactory threadFactory = r -> {
            final Thread t = new Thread(r, "oo-log-flusher");
            t.setDaemon(true);
            return t;
        };
        scheduler = new ScheduledThreadPoolExecutor(1, threadFactory);
        scheduler.scheduleWithFixedDelay(this::flush, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);

        addInfo("OpenObserve Appender 已启动，端点: " + ingestionUrl);
        super.start();
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
        final long dropped = droppedCount.get();
        if (dropped > 0) {
            addWarn("OpenObserve Appender 关闭，累计丢弃日志: " + dropped + " 条");
        }
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            final String json = serialize(event);
            if (!queue.offer(json)) {
                droppedCount.incrementAndGet();
            }
        } catch (RuntimeException ex) {
            addWarn("OpenObserve Appender 序列化日志失败: " + ex.getMessage());
        }
    }

    /**
     * 批量推送队列中待发送的日志到 OpenObserve _json API
     */
    private void flush() {
        final List<String> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return;
        }

        final StringBuilder body = new StringBuilder(batch.size() * 256 + 2);
        body.append('[');
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(batch.get(i));
        }
        body.append(']');

        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(ingestionUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            if (authHeader != null) {
                builder.header("Authorization", authHeader);
            }
            final HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            final int code = response.statusCode();
            if (code < 200 || code >= 300) {
                addWarn("OpenObserve 推送失败 status=" + code + " body=" + response.body());
            }
        } catch (IOException ex) {
            addWarn("OpenObserve 推送 I/O 异常: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            addWarn("OpenObserve 推送被中断");
        }
    }

    /**
     * 将日志事件序列化为 OpenObserve 兼容的 JSON
     */
    private String serialize(ILoggingEvent event) {
        final JSONObject obj = new JSONObject();
        obj.put("timestamp", event.getTimeStamp() * 1000L);
        obj.put("service", service != null ? service : "springai-rag");
        obj.put("level", event.getLevel().toString());
        obj.put("logger", event.getLoggerName());
        obj.put("thread", event.getThreadName());

        // 结构化事件日志（logger = rag.telemetry）：StructuredLog 把事件序列化成 JSON 串打 log.info，
        // 这里把 message 解析回 JSON 合并到顶层字段（_event/rag_step/step_id/data/duration_ms），
        // 避免 data 等被当成字符串二次转义；非结构化日志仍走 message。
        final String formatted = event.getFormattedMessage();
        if ("rag.telemetry".equals(event.getLoggerName())) {
            try {
                JSONObject parsed = new JSONObject(formatted);
                obj.putAll(parsed);
                // 可读 message（OpenObserve 列表预览用），详情看顶层字段
                Object evt = parsed.get("_event");
                Object step = parsed.get("rag_step");
                obj.put("message", step != null ? evt + " " + step : evt);
            } catch (Exception e) {
                obj.put("message", formatted);
            }
        } else {
            obj.put("message", formatted);
        }

        // MDC（包含 traceId, spanId 等 OTel 上下文）
        final Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null && !mdc.isEmpty()) {
            obj.putAll(mdc);
            // OpenObserve 标准关联字段：日志按 trace_id/span_id 与 trace 及 span 精确关联
            // （Spring Boot Micrometer OTel 注入的 MDC 键是驼峰 traceId/spanId，OpenObserve 认下划线）
            final String traceId = mdc.get("traceId");
            final String spanId = mdc.get("spanId");
            if (traceId != null) {
                obj.put("trace_id", traceId);
            }
            if (spanId != null) {
                obj.put("span_id", spanId);
            }
        }

        // 异常堆栈（ThrowableProxyUtil.asString 自动递归 cause 链）
        if (event.getThrowableProxy() != null) {
            obj.put("exception", ThrowableProxyUtil.asString(event.getThrowableProxy()));
            // 额外提取类名方便检索
            if (event.getThrowableProxy() instanceof ThrowableProxy) {
                final Throwable t = ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
                if (t != null) {
                    obj.put("exceptionClass", t.getClass().getName());
                }
            }
        }

        return obj.toString();
    }

    // ========== 属性注入（logback-spring.xml 的 <xxx> 标签） ==========

    public void setUrl(String url) {
        this.url = url;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public void setService(String service) {
        this.service = service;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setFlushIntervalMillis(long flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }
}
