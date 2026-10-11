package com.jjx.ai.llmobservability.backends.langfuse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Langfuse 回读探针（手动运行，参照 ragent 项目 LangfuseReadbackProbe 的验证方法）：
 * 用与 {@link LangfuseOtlpAutoConfiguration} 修复后完全相同的请求头（Basic 认证 +
 * {@code x-langfuse-ingestion-version: 4}）直发一棵三层 OTLP JSON span 树，验证后端摄取路径。
 *
 * <p><b>为什么手动</b>：需要本机起 Langfuse 栈，CI 与日常构建都不该依赖它。设好环境变量后运行：</p>
 * <pre>
 * LANGFUSE_PROBE_PUBLIC_KEY=pk-lf-xxx \
 * LANGFUSE_PROBE_SECRET_KEY=sk-lf-xxx \
 * mvn -pl llm-observability-backends test -Dtest=LangfuseReadbackProbe
 * </pre>
 * <p>endpoint 与密钥缺省取 {@code LANGFUSE_PROBE_ENDPOINT}（默认 http://localhost:3000/api/public/otel/v1/traces）。</p>
 *
 * <p><b>探针树携带的语义</b>（都是库里主张的契约，改键名前先跑一遍）：</p>
 * <ul>
 *   <li>每个 span 都写 {@code langfuse.session.id} / {@code langfuse.user.id}——Langfuse 不回填
 *       子节点身份，漏写那层 observation 级过滤查不到</li>
 *   <li>span event 与其属性副本（{@code agentframework.event.*}）成对出现——event 本体会被
 *       Langfuse 丢弃，属性副本是唯一可见的一份</li>
 *   <li>{@code gen_ai.conversation.id} 与 {@code langfuse.session.id} 同值——两把键落同一列</li>
 * </ul>
 *
 * <p><b>入库后怎么验证</b>（Langfuse v4 events_only 模式下 REST API 返回 not available，直接查存储）：</p>
 * <pre>
 * # ClickHouse：看属性落列情况（session_id 应为 3、metadata 里应有 event 副本键）
 * docker exec langfuse-clickhouse clickhouse-client -q "
 * SELECT name, session_id, metadata_names FROM events_full
 * WHERE service_name='llm-obs-probe' ORDER BY start_time"
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "LANGFUSE_PROBE_PUBLIC_KEY", matches = ".+")
class LangfuseReadbackProbe {

    private static final String ENDPOINT = System.getenv()
            .getOrDefault("LANGFUSE_PROBE_ENDPOINT", "http://localhost:3000/api/public/otel/v1/traces");

    private static final String SERVICE_NAME = "llm-obs-probe";

    /** 三层树 + event 双写 + 每 span 身份，一次性覆盖全部主张 */
    @Test
    void shouldPostProbeTreeWithIngestionHeader() throws Exception {
        long now = System.currentTimeMillis();
        String nowNano = now + "000000";
        String sessionValue = "probe-session-" + now;
        String userValue = "probe-user";
        String traceId = hex32(now);

        String body = """
                {"resourceSpans":[{"resource":{"attributes":[
                     {"key":"service.name","value":{"stringValue":"%s"}}]},
                   "scopeSpans":[{"scope":{"name":"probe"},"spans":[
                     {"traceId":"%s","spanId":"1111111111111111","name":"probe_root","kind":1,
                      "startTimeUnixNano":"%s","endTimeUnixNano":"%s",
                      "attributes":[
                        {"key":"langfuse.observation.type","value":{"stringValue":"agent"}},
                        {"key":"langfuse.session.id","value":{"stringValue":"%s"}},
                        {"key":"langfuse.user.id","value":{"stringValue":"%s"}},
                        {"key":"gen_ai.conversation.id","value":{"stringValue":"%s"}}]},
                     {"traceId":"%s","spanId":"2222222222222222","parentSpanId":"1111111111111111",
                      "name":"probe_child","kind":1,
                      "startTimeUnixNano":"%s","endTimeUnixNano":"%s",
                      "attributes":[
                        {"key":"langfuse.observation.type","value":{"stringValue":"span"}},
                        {"key":"langfuse.session.id","value":{"stringValue":"%s"}},
                        {"key":"langfuse.user.id","value":{"stringValue":"%s"}}],
                      "events":[{"name":"probe_event","timeUnixNano":"%s","attributes":[
                        {"key":"detail","value":{"stringValue":"event-body-will-be-dropped"}}]}]},
                     {"traceId":"%s","spanId":"3333333333333333","parentSpanId":"2222222222222222",
                      "name":"probe_grandchild","kind":1,
                      "startTimeUnixNano":"%s","endTimeUnixNano":"%s",
                      "attributes":[
                        {"key":"langfuse.observation.type","value":{"stringValue":"tool"}},
                        {"key":"langfuse.session.id","value":{"stringValue":"%s"}},
                        {"key":"langfuse.user.id","value":{"stringValue":"%s"}},
                        {"key":"agentframework.event.probe_event","value":{"stringValue":"detail=event-attribute-copy"}}]}
                   ]}]}]}
                """.formatted(SERVICE_NAME,
                        traceId, nowNano, nowNano, sessionValue, userValue, sessionValue,
                        traceId, nowNano, nowNano, sessionValue, userValue, nowNano,
                        traceId, nowNano, nowNano, sessionValue, userValue);

        String publicKey = System.getenv("LANGFUSE_PROBE_PUBLIC_KEY");
        String secretKey = System.getenv("LANGFUSE_PROBE_SECRET_KEY");
        String credential = Base64.getEncoder()
                .encodeToString((publicKey + ":" + (secretKey == null ? "" : secretKey))
                        .getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + credential)
                .header("x-langfuse-ingestion-version", "4")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

        // 断言到 2xx 为止：入库是异步的（web 收包 → MinIO/Redis → worker → ClickHouse），
        // 「查得到」要靠类注释里的 ClickHouse 命令人工回读，这里只验摄取入口收下
        assertThat(response.statusCode()).isBetween(200, 299);
        System.out.println("[probe] traceId=" + traceId + " session=" + sessionValue
                + " → 打开 Langfuse UI 按 session 过滤，或查 ClickHouse events_full");
    }

    /** 时间戳展开成 32 位 hex traceId：每次运行唯一，便于在 UI 里按 trace 精确找到这棵探针树 */
    private static String hex32(long value) {
        String hex = Long.toHexString(value);
        return "0".repeat(16) + "0".repeat(16 - hex.length()) + hex;
    }
}
