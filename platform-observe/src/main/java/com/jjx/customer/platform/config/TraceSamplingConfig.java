package com.jjx.customer.platform.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * trace 导出白名单：HTTP 入口只导出 /chat/stream，其余请求（会话 CRUD、文档上传、actuator、
 * Knife4j 等）整条 trace 不发给 Langfuse / OpenObserve，对话链路不再被普通接口刷屏。
 *
 * <p>用导出侧过滤（{@link SpanExportingPredicate}，Spring Boot 收进 CompositeSpanExporter）
 * 而非 Sampler：micrometer bridge 在 startSpan <b>之后</b>才补 span 名/属性，采样时点拿到的是
 * <span>&lt;unspecified span name&gt;</span> + 空 attributes，按路径判断无从下手；导出时
 * FinishedSpan 的名/tag 已完整。</p>
 *
 * <p>注意：过滤按单个 span 独立判断。其余 HTTP 接口当前都没有业务子 span，丢 root 即丢整条；
 * 若将来给非 chat 接口加业务埋点，需一并考虑父 span 被滤后子 span 变无根碎片的问题。</p>
 */
@Configuration
public class TraceSamplingConfig {

    /** 导出的 HTTP 入口路径（前缀匹配；以后要给其它接口开 trace 就加到这里） */
    private static final List<String> EXPORTED_PATH_PREFIXES = List.of("/chat/stream");

    /** OTel 语义约定的 path 属性（字符串字面量，不依赖 semconv 类名随版本变动） */
    private static final String URL_PATH = "url.path";
    private static final String HTTP_ROUTE = "http.route";

    @Bean
    public SpanExportingPredicate chatStreamOnlyPredicate() {
        return span -> {
            if (span.getKind() == Span.Kind.SERVER) {
                String path = resolvePath(span);
                return path == null || EXPORTED_PATH_PREFIXES.stream().anyMatch(path::startsWith);
            }
            return true;
        };
    }

    /** 优先取语义属性 url.path / http.route，兜底从 span 名解析（格式："http delete /chat/..."）。 */
    private static String resolvePath(FinishedSpan span) {
        Map<String, String> tags = span.getTags();
        String path = tags.get(URL_PATH);
        if (path == null || path.isBlank()) {
            path = tags.get(HTTP_ROUTE);
        }
        if (path == null || path.isBlank()) {
            String name = span.getName();
            if (name != null && name.startsWith("http ")) {
                String[] parts = name.split(" ");
                if (parts.length >= 3) {
                    path = parts[2];
                }
            }
        }
        return path;
    }
}
