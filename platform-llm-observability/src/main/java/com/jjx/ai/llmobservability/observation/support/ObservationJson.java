package com.jjx.ai.llmobservability.observation.support;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.ReflectionAccessFilter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;

/**
 * 观测 JSON 序列化的唯一出口：全库共享一个 Gson 实例，杜绝各自 new 出的配置漂移。
 *
 * <p><b>为什么需要它</b>：观测数据来自任意业务对象（槽位快照、运行结局、工具结果），
 * 这些对象普遍带 {@code java.time} 字段。默认 {@code new Gson()} 在 JDK 17+ 上反射访问
 * {@code java.time.Instant#seconds} 等内部字段会被模块系统拒绝，抛
 * {@code InaccessibleObjectException}——摘要层只能整对象降级成 {@code {type,error}} 占位，
 * 输入输出的正文全部丢失（2026-10-11 真实 trace 上 node:、workflow:、tool: 三类 span 全中招）。</p>
 *
 * <p><b>两道防线</b>：</p>
 * <ol>
 *   <li>常用 {@code java.time} 类型显式注册为 ISO-8601 字符串序列化器——时间字段以可读文本保留，
 *       而不是被跳过（{@link ReflectionAccessFilter} 单独用只会把字段静默丢掉）。</li>
 *   <li>{@link ReflectionAccessFilter#BLOCK_INACCESSIBLE_JAVA} 兜住未注册的 JDK 内部类型：
 *       跳字段而非抛异常，最坏情况丢一个叶子字段，不会毁掉整个摘要对象。</li>
 * </ol>
 *
 * <p><b>协作</b>：由 {@code Summarizer}（摘要）、{@code SpanAttributeExporter}（span 属性）、
 * {@code TelemetryStructuredLog}（结构化日志）、Spring AI 补写组件等统一使用；
 * 需要 Gson 实例做解析（fromJson）的调用方用 {@link #gson()}。</p>
 */
public final class ObservationJson {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .addReflectionAccessFilter(ReflectionAccessFilter.BLOCK_INACCESSIBLE_JAVA)
            .registerTypeAdapter(Instant.class, serializer(Instant::toString))
            .registerTypeAdapter(OffsetDateTime.class, serializer(OffsetDateTime::toString))
            .registerTypeAdapter(ZonedDateTime.class, serializer(ZonedDateTime::toString))
            .registerTypeAdapter(LocalDateTime.class, serializer(LocalDateTime::toString))
            .registerTypeAdapter(LocalDate.class, serializer(LocalDate::toString))
            .registerTypeAdapter(LocalTime.class, serializer(LocalTime::toString))
            .registerTypeAdapter(OffsetTime.class, serializer(OffsetTime::toString))
            .registerTypeAdapter(Duration.class, serializer(Duration::toString))
            .registerTypeAdapter(Period.class, serializer(Period::toString))
            .registerTypeAdapter(Year.class, serializer(Year::toString))
            .registerTypeAdapter(YearMonth.class, serializer(YearMonth::toString))
            .registerTypeAdapter(MonthDay.class, serializer(MonthDay::toString))
            .create();

    private ObservationJson() {
    }

    /** 共享 Gson 实例（需要 fromJson 解析时直接用它）。 */
    public static Gson gson() {
        return GSON;
    }

    /** 序列化为 JSON 字符串；失败由调用方决定降级策略（本方法只负责用对配置）。 */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /** 序列化为 JsonElement 树（摘要层递归遍历用）。 */
    public static JsonElement toJsonTree(Object value) {
        return GSON.toJsonTree(value);
    }

    /** {@code java.time} 类型统一出境形态：ISO-8601 文本。 */
    private static <T> JsonSerializer<T> serializer(java.util.function.Function<T, String> toString) {
        return (src, type, context) -> src == null ? null : new JsonPrimitive(toString.apply(src));
    }
}
