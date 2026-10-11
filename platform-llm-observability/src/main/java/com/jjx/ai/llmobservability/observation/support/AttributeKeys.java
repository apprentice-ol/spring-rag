package com.jjx.ai.llmobservability.observation.support;

/**
 * 框架输出的全部 attribute key 的唯一来源（不含业务 span 名，业务键值由宿主定义）。
 *
 * <p><b>命名规则</b>（框架要独立于任何业务项目复用，因此非业务字段不使用业务前缀）：</p>
 * <ol>
 *   <li><b>OTel 有标准 → 直接用标准 key</b>：会话/用户用通用 registry 的 {@code session.id}/{@code user.id}
 *       （Langfuse 亦原生识别）；LLM 语义用 GenAI 语义约定（{@code gen_ai.input/output.messages}、
 *       {@code gen_ai.client.response.time_to_first_token}、{@code gen_ai.conversation.id}、
 *       {@code gen_ai.operation.name}、{@code gen_ai.request.model}、{@code gen_ai.usage.*} 等）。</li>
 *   <li><b>OTel 无标准但生态通用</b>（多数框架沿用同一叫法）：按大多数框架的 key 原样使用——
 *       {@code gen_ai.prompt}/{@code gen_ai.completion} 是 OTel GenAI 早期约定，Langfuse/OpenLLMetry/
 *       Spring AI 等仍普遍识别，沿用原名；tags / metadata / release 这类通用词走命名空间前缀，
 *       由 backends 模块映射为后端认的 key（如 {@code langfuse.trace.tags}）。</li>
 *   <li><b>生态连通用叫法都没有</b>（首 token 时间戳、step 等仅本项目需要）：走命名空间前缀兜底。</li>
 * </ol>
 *
 * <p><b>命名空间</b>：{@code telemetry.attribute-namespace} 配置，未配置时取 {@code spring.application.name}
 * （本项目即 {@code springai-rag} → {@code springai-rag.trace.tags}），再缺省 {@code telemetry}。
 * 由 {@code TelemetryAutoConfiguration} 在启动期（开任何 span 前）经 {@link #configureNamespace(String)}
 * 一次性生效，运行期只读——与 {@link SpanIoLimits} 同一套启动期配置模式。</p>
 */
public final class AttributeKeys {

    private AttributeKeys() {
    }

    /** 会话标识（OTel 通用 registry 标准属性；Langfuse 原生映射 sessionId，OpenObserve 拍平 session_id）。 */
    public static final String SESSION_ID = "session.id";

    /** 用户标识（OTel 通用 registry 标准属性；Langfuse 原生映射 userId，OpenObserve 拍平 user_id）。 */
    public static final String USER_ID = "user.id";

    /** trace 级输入（OTel GenAI 标准；OpenObserve 原生识别，Langfuse 由 backends 映射）。 */
    public static final String TRACE_INPUT = "gen_ai.input.messages";

    /** trace 级输出（OTel GenAI 标准；同上）。 */
    public static final String TRACE_OUTPUT = "gen_ai.output.messages";

    /** TTFT 时长秒数（OTel GenAI 标准属性）。 */
    public static final String TTFT_SECONDS = "gen_ai.client.response.time_to_first_token";

    /** LLM span 上的对话标识（OTel GenAI 标准属性；OpenObserve 拍平 gen_ai_conversation_id）。 */
    public static final String GEN_AI_CONVERSATION_ID = "gen_ai.conversation.id";

    // ==================== OTel GenAI 标准属性 ====================

    /** LLM 操作名（OTel GenAI 标准；如 chat / embeddings）。 */
    public static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";

    /** 模型提供方（OTel GenAI 标准；如 openai / deepseek / bailian）。 */
    public static final String GEN_AI_SYSTEM = "gen_ai.system";

    /** 请求模型名（OTel GenAI 标准；Langfuse / OpenObserve 原生识别为 LLM span）。 */
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";

    /** 响应模型名（OTel GenAI 标准）。 */
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";

    /** 请求 max_tokens（OTel GenAI 标准）。 */
    public static final String GEN_AI_REQUEST_MAX_TOKENS = "gen_ai.request.max_tokens";

    /** 请求 temperature（OTel GenAI 标准）。 */
    public static final String GEN_AI_REQUEST_TEMPERATURE = "gen_ai.request.temperature";

    /** 响应 finish_reasons（OTel GenAI 标准）。 */
    public static final String GEN_AI_RESPONSE_FINISH_REASONS = "gen_ai.response.finish_reasons";

    /** 输入 token 数（OTel GenAI 标准）。 */
    public static final String GEN_AI_USAGE_INPUT_TOKENS = "gen_ai.usage.input_tokens";

    /** 输出 token 数（OTel GenAI 标准）。 */
    public static final String GEN_AI_USAGE_OUTPUT_TOKENS = "gen_ai.usage.output_tokens";

    /** 总 token 数（OTel GenAI 标准）。 */
    public static final String GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";

    /** GenAI 客户端操作 span 名（OTel GenAI 标准；如 gen_ai.client.operation）。 */
    public static final String GEN_AI_CLIENT_OPERATION = "gen_ai.client.operation";

    // ==================== OTel 无标准但生态通用（按大多数框架的叫法） ====================

    /** 请求 prompt 原文（OTel GenAI 早期约定，Langfuse/OpenLLMetry/Spring AI 等普遍识别）。 */
    public static final String GEN_AI_PROMPT = "gen_ai.prompt";

    /** 响应 completion 原文（同上；Spring AI 1.1.x 需自行补写）。 */
    public static final String GEN_AI_COMPLETION = "gen_ai.completion";

    // ==================== 自定义 step 级 IO（既有约定，保持稳定） ====================

    /** step 输入字段（非 OTel 标准；OpenObserve Trace Input/Output 面板读取）。 */
    public static final String STEP_INPUT = "input";

    /** step 输出字段（非 OTel 标准；同上）。 */
    public static final String STEP_OUTPUT = "output";

    private static volatile String namespace = "telemetry";

    /** 覆盖命名空间（启动期配置一次；空白忽略，非法字符替换为下划线避免属性 key 嵌套）。 */
    public static void configureNamespace(String ns) {
        if (ns == null || ns.isBlank()) {
            return;
        }
        namespace = ns.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** 当前命名空间（默认 telemetry）。 */
    public static String namespace() {
        return namespace;
    }

    /** trace 标签汇总（生态通用词 tags；Langfuse 由 backends 映射 langfuse.trace.tags）。 */
    public static String traceTags() {
        return namespace + ".trace.tags";
    }

    /** trace 级维度（生态通用词 metadata；Langfuse 由 backends 映射 langfuse.trace.metadata.*）。 */
    public static String traceMetadata(String key) {
        return namespace + ".trace.metadata." + key;
    }

    /** trace 元数据 key 前缀（供 startsWith 判断与后缀截取）。 */
    public static String traceMetadataPrefix() {
        return namespace + ".trace.metadata.";
    }

    /** 发布版本（Sentry/Langfuse 通用叫法 release；Langfuse 由 backends 映射 langfuse.release）。 */
    public static String release() {
        return namespace + ".release";
    }

    /** 首 token 时间戳（ISO-8601；生态无通用叫法，仅 Langfuse completion_start_time 需要，backends 映射）。 */
    public static String firstTokenAt() {
        return namespace + ".first_token_at";
    }

    /** 当前步骤名（挂到框架建的 LLM span / OTLP 日志属性上做步骤关联；生态无标准）。 */
    public static String step() {
        return namespace + ".step";
    }

    /** 当前步骤 span id（OTLP 日志属性；OTel 日志记录本身带 span_id 字段，此 key 供非 OTel 后端关联）。 */
    public static String stepId() {
        return namespace + ".step_id";
    }

    /** step 耗时分布指标名（Micrometer Timer，OTel 无此概念的标准化名称，走命名空间）。 */
    public static String stepDurationMetric() {
        return namespace + ".step.duration";
    }
}
