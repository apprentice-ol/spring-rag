package com.nageoffer.ai.obs.example;

import com.nageoffer.ai.obs.backends.openobserve.OpenObserveQueryClient;
import com.nageoffer.ai.obs.backends.openobserve.dto.TraceLogEntry;
import com.nageoffer.ai.obs.backends.openobserve.dto.TraceSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OpenObserve 查询客户端接口的使用示例（读取侧）。
 *
 * <p>展示按 traceId 查日志与查 span 链路两个原语；查询失败返回空列表，不抛异常。</p>
 */
@Component
public class OpenObserveQueryExample {

    private static final Logger log = LoggerFactory.getLogger(OpenObserveQueryExample.class);

    private final ObjectProvider<OpenObserveQueryClient> clientProvider;

    /**
     * 构造示例组件。
     *
     * @param clientProvider 查询客户端提供器（条件 Bean）
     */
    public OpenObserveQueryExample(ObjectProvider<OpenObserveQueryClient> clientProvider) {
        this.clientProvider = clientProvider;
    }

    /**
     * 示例：按 traceId 查日志并打印摘要。
     *
     * @param traceId 链路 ID
     */
    public void showLogs(String traceId) {
        List<TraceLogEntry> logs = requireClient().searchLogsByTraceId(traceId, 50);
        log.info("[示例] traceId={} 日志 {} 条", traceId, logs.size());
        for (TraceLogEntry entry : logs) {
            log.info("[示例]   {} {} {} {}", entry.timestamp(), entry.level(), entry.logger(), entry.message());
        }
    }

    /**
     * 示例：按 traceId 查 span 链路并打印摘要。
     *
     * @param traceId 链路 ID
     */
    public void showSpans(String traceId) {
        List<TraceSpan> spans = requireClient().searchSpansByTraceId(traceId, 100);
        log.info("[示例] traceId={} span {} 条", traceId, spans.size());
        for (TraceSpan span : spans) {
            log.info("[示例]   {} {} {}ms status={}", span.spanId(), span.name(), span.durationUs() / 1000, span.status());
        }
    }

    private OpenObserveQueryClient requireClient() {
        OpenObserveQueryClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("OpenObserveQueryClient 未注册：请确认 obs.openobserve.query-enabled=true");
        }
        return client;
    }
}
