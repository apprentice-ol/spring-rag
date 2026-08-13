package com.nageoffer.ai.rag.config.telemetry;

import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 在 {@code ChatController.stream} 入口（请求线程，Handler 执行处于 HTTP server observation scope 内）
 * 开启对话 trace：捕获 HTTP 根 span + MDC(conversation_id) + ambient {@link ConversationTrace} + 设 input(用户问题)。
 *
 * <p><b>为何不用 Filter</b>：Filter 必须排在 Spring Boot 的 {@code ServerHttpObservationFilter} 之后才能拿到
 * server span（依赖 @Order，易错）；而 Advisor 在 Handler 方法执行点，server observation 必定已开，
 * {@code Span.current()} 稳定为根 span。且 {@code question}/{@code conversationId} 直接取自方法入参，
 * 无需解析 request。
 *
 * <p>开启后，{@code ChatController} 的 {@code ContextSnapshot.captureAll().wrap(虚拟线程任务)} 会把 ambient
 * scope 透传到虚拟线程，{@code Hooks.enableAutomaticContextPropagation} 再透传到 Reactor 流式回调。
 * 业务在回答完成点通过 {@link RagTelemetry#currentConversationTrace()} 捕获引用后显式写 output
 * （流式分支必须在 subscribe 前捕获），input 已在此处写入；saveMessage 只负责持久化。
 *
 * <p>{@code conversationId} 入参可能为 null（controller 内部生成最终值）；此时仅设 input + ambient scope，
 * MDC 的 conversation_id 缺失只影响日志聚合，不阻断 trace 的核心 input/output。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversationTraceAdvisor {

    private final RagTelemetry telemetry;

    public ConversationTraceAdvisor(RagTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** {@code ChatController.stream(question, conversationId, agent)}。 */
    @Around("execution(* com.nageoffer.ai.rag.chat.controller.ChatController.stream(..))")
    public Object openConversationTrace(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        String question = args.length > 0 && args[0] instanceof String s ? s : null;
        String conversationId = args.length > 1 && args[1] instanceof String s ? s : null;
        // conversationId 空则在此统一生成并回填入参：让 trace（MDC/attribute）与业务（controller/持久化）
        // 用同一个 id，避免“日志 conversation_id 为空、消息表却是 UUID”的不一致。
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
            if (args.length > 1) {
                args[1] = conversationId;
            }
        }
        telemetry.startConversation(conversationId, question);
        try {
        // 用（可能已回填的）入参 proceed，controller 内部的兜底生成逻辑据此不再触发
            return pjp.proceed(args);
        }
        finally {
            ConversationTraceAccessor.HOLDER.remove();
            MDC.remove("conversation_id");
        }
    }
}
