package com.nageoffer.ai.obs.trigger;

import com.nageoffer.ai.obs.Telemetry;
import com.nageoffer.ai.obs.propagation.ConversationContextAccessor;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link TraceConversation} 注解切面：在入口方法（请求线程，HTTP server span 为 current）开启对话 trace。
 *
 * <p><b>所属维度</b>：①触发（只产事件/开作用域，不落地）。</p>
 *
 * <p><b>职责</b>：捕获 HTTP 根 span + MDC(conversation_id) + ambient {@code ConversationContext} + 设 input(用户问题)。
 * conversationId 空则生成 UUID 并回填入参（让 trace 与业务用同一个 id）。finally 清理 ambient HOLDER + MDC。</p>
 *
 * <p><b>协作</b>：开启后 ambient 经 context-propagation 透传到虚拟线程/Reactor 回调；业务经 {@link Telemetry#conversationOutput} 写 output。</p>
 *
 * <p><b>不做什么</b>：不开 step span；不写 output；不感知检索/回答细节。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConversationAdvisor {

    private final Telemetry telemetry;

    public ConversationAdvisor(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around("@annotation(com.nageoffer.ai.obs.trigger.TraceConversation)")
    public Object openConversation(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        TraceConversation tc = signature.getMethod().getAnnotation(TraceConversation.class);
        Object[] args = pjp.getArgs();
        String question = stringAt(args, tc.questionIndex());
        String conversationId = stringAt(args, tc.conversationIdIndex());
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString();
            if (tc.conversationIdIndex() >= 0 && tc.conversationIdIndex() < args.length) {
                args[tc.conversationIdIndex()] = conversationId;
            }
        }
        telemetry.beginConversation(conversationId, question);
        try {
            return pjp.proceed(args);
        } finally {
            ConversationContextAccessor.HOLDER.remove();
            MDC.remove("conversation_id");
        }
    }

    private static String stringAt(Object[] args, int index) {
        if (index < 0 || index >= args.length) {
            return null;
        }
        return args[index] instanceof String s ? s : null;
    }
}
