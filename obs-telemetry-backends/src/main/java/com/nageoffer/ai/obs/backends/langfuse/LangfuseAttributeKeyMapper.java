package com.nageoffer.ai.obs.backends.langfuse;

import com.nageoffer.ai.obs.observation.exporter.SpanAttributeKeyMapper;
import com.nageoffer.ai.obs.observation.support.OtelKeys;

/**
 * Langfuse 的属性 key 兼容映射器：应用侧通用 key（OTel 标准 / 命名空间前缀）→ Langfuse 数据模型字段（{@code langfuse.*}）。
 *
 * <p><b>在应用侧而非 collector 做映射的原因</b>：collector 是可选组件（{@code obs.collector.enabled=false}
 * 时应用直连），映射放 collector 会导致两种模式行为不一致。本映射器注册为 bean 后，
 * {@code SpanAttributeExporter}（事件属性写入）与 {@code BaggageAttributeSpanProcessor}（baggage 落 span）
 * 自动追加写 Langfuse 专属 key——无论走 collector 还是直连，Langfuse 收到的字段完全相同。</p>
 *
 * <p><b>映射表</b>（对照 Langfuse OTLP attribute mapping 文档）：</p>
 * <ul>
 *   <li>{@code gen_ai.input/output.messages} → {@code langfuse.observation.input/output}（trace 级 IO，根 span）。
 *       兼容处理：应用按 OTel GenAI 新标准写消息属性，Langfuse 只认识旧标准 {@code gen_ai.prompt/completion}
 *       与自家 {@code langfuse.observation.*}，取后者最稳。</li>
 *   <li>{@code {ns}.trace.tags} → {@code langfuse.trace.tags}（列表过滤）</li>
 *   <li>{@code {ns}.trace.metadata.*} → {@code langfuse.trace.metadata.*}（一级可过滤 metadata；
 *       未加前缀的普通属性会掉进 {@code metadata.attributes} 不可过滤）</li>
 *   <li>{@code {ns}.first_token_at} → {@code langfuse.observation.completion_start_time}（TTFT，世代视图展示）</li>
 *   <li>{@code {ns}.release} → {@code langfuse.release}（版本/环境区分）</li>
 * </ul>
 *
 * <p><b>无需映射的</b>：{@code session.id} / {@code user.id}（OTel 通用标准，Langfuse 原生识别）；
 * {@code gen_ai.request.model} / {@code gen_ai.usage.*} 等（GenAI 标准，Langfuse 原生识别）。</p>
 *
 * <p><b>开关</b>：{@code obs.langfuse.attribute-mapping.enabled=false} 停用（默认开）。
 * {@code {ns}} 为 {@code obs.attribute-namespace} 命名空间（运行期经 {@link OtelKeys} 解析，
 * 故本类不静态缓存前缀）。</p>
 */
public class LangfuseAttributeKeyMapper implements SpanAttributeKeyMapper {

    @Override
    public String map(String key) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case OtelKeys.TRACE_INPUT:
                return "langfuse.observation.input";
            case OtelKeys.TRACE_OUTPUT:
                return "langfuse.observation.output";
            case OtelKeys.TTFT_SECONDS:
                return null; // Langfuse 无 TTFT 属性；first_token_at 时间戳已映射 completion_start_time
            default:
                // 命名空间前缀 key（运行期解析，不能进静态表）
        }
        if (key.equals(OtelKeys.traceTags())) {
            return "langfuse.trace.tags";
        }
        if (key.equals(OtelKeys.release())) {
            return "langfuse.release";
        }
        if (key.equals(OtelKeys.firstTokenAt())) {
            return "langfuse.observation.completion_start_time";
        }
        String metadataPrefix = OtelKeys.traceMetadataPrefix();
        if (key.startsWith(metadataPrefix)) {
            return "langfuse.trace.metadata." + key.substring(metadataPrefix.length());
        }
        return null;
    }
}
