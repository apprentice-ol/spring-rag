package com.jjx.ai.llmobservability.observation.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个请求/对话入口方法，由 {@link TelemetryConversationAspect} 切入开启对话 trace 作用域。
 *
 * <p><b>所属维度</b>：①触发（标记注解）。</p>
 *
 * <p><b>职责</b>：切面从方法入参按 index 取 question / conversationId，开启对话 trace（捕获 server span + 设 trace input）。
 * conversationId 为空时由切面统一生成并回填入参。</p>
 *
 * <p><b>协作</b>：标注在 Controller 入口方法上；{@code conversationId} 入参可能为 null，切面回填最终值。</p>
 *
 * <p><b>不做什么</b>：不描述 span 语义（那是 {@code TelemetryStep} 的事）。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TelemetryConversation {

    /** question 参数在方法签名中的下标（默认 0）。 */
    int questionIndex() default 0;

    /** conversationId 参数在方法签名中的下标（默认 1）。为空时切面生成 UUID 并回填。 */
    int conversationIdIndex() default 1;
}
