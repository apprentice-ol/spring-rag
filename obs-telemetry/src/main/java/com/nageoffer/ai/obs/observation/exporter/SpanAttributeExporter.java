package com.nageoffer.ai.obs.observation.exporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ReflectionAccessFilter;
import com.nageoffer.ai.obs.observation.support.OtelKeys;
import com.nageoffer.ai.obs.observation.span.SpanWriter;
import com.nageoffer.ai.obs.observation.event.ObsEvent;
import java.util.List;
import org.springframework.core.annotation.Order;

/**
 * 写 span attribute 的 exporter（发·出口 A）。
 *
 * <p><b>所属维度</b>：发（{@link ObservationExporter} 内置实现，@Order(10)）。</p>
 *
 * <p><b>职责</b>：把事件写为 span attribute（data 已被 processor 摘要/截断，直接落）：
 * <ul>
 *   <li>STEP_INPUT → {@code input}；STEP_OUTPUT → {@code output}（OpenObserve trace Input/Output 面板读）。</li>
 *   <li>TRACE_IO → {@code event.ioKey}（gen_ai.input/output.messages，OTel GenAI 标准）。</li>
 *   <li>ATTRIBUTE → {@code target.setTag}（低基数标签，可聚合）。</li>
 * </ul>
 * TRACE_IO / ATTRIBUTE 命中 {@link SpanAttributeKeyMapper} 映射时<b>追加</b>写后端专属 key
 * （原 key 保留）——应用写通用 key，后端（如 Langfuse）自动获得自己认识的字段，与 collector 无关。</p>
 *
 * <p><b>不做什么</b>：不加工 data（processor 已做）；CUSTOM 不写 attribute（StructuredLogExporter 发日志）。</p>
 */
@Order(10)
public class SpanAttributeExporter implements ObservationExporter {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .addReflectionAccessFilter(ReflectionAccessFilter.BLOCK_INACCESSIBLE_JAVA)
            .create();

    private final List<SpanAttributeKeyMapper> keyMappers;

    public SpanAttributeExporter(List<SpanAttributeKeyMapper> keyMappers) {
        this.keyMappers = keyMappers == null ? List.of() : keyMappers;
    }

    @Override
    public void export(ObsEvent event, SpanWriter target) {
        switch (event.getType()) {
            case STEP_INPUT -> {
                if (event.getData() != null) {
                    target.setAttribute(OtelKeys.STEP_INPUT, stringifyInput(event.getData()));
                }
            }
            case STEP_OUTPUT -> {
                if (event.getData() != null) {
                    target.setAttribute(OtelKeys.STEP_OUTPUT, stringifyOutput(event.getData()));
                }
            }
            case TRACE_IO -> {
                if (event.getData() != null) {
                    String value = OtelKeys.TRACE_INPUT.equals(event.getIoKey())
                            ? stringifyInput(event.getData())
                            : stringifyOutput(event.getData());
                    target.setAttribute(event.getIoKey(), value);
                    writeMapped(target, event.getIoKey(), value);
                }
            }
            case ATTRIBUTE -> {
                String value = event.getData() == null ? "" : event.getData().toString();
                target.setTag(event.getIoKey(), value);
                writeMapped(target, event.getIoKey(), value);
            }
            default -> { /* CUSTOM：不写 attribute */ }
        }
    }

    /** key 命中映射时追加写后端专属 key（原 key 已由调用方写入）。 */
    private void writeMapped(SpanWriter target, String key, String value) {
        for (SpanAttributeKeyMapper mapper : keyMappers) {
            String mapped = mapper.map(key);
            if (mapped != null) {
                target.setAttribute(mapped, value);
            }
        }
    }

    /** 输入字段统一 JSON 序列化；序列化失败时降级 toString，保证观测不阻断业务。 */
    private String stringifyInput(Object data) {
        try {
            return GSON.toJson(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }

    /** 输出字段保留字符串原文，其他对象 JSON 序列化。 */
    private String stringifyOutput(Object data) {
        if (data instanceof CharSequence cs) {
            return cs.toString();
        }
        try {
            return GSON.toJson(data);
        } catch (Exception e) {
            return String.valueOf(data);
        }
    }
}
