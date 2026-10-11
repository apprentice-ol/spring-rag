package com.jjx.ai.llmobservability.observation.propagation;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import reactor.core.publisher.Hooks;

/**
 * Micrometer Context Propagation 装配。维度④的地基。
 *
 * <p><b>所属维度</b>：④传播（装配）。</p>
 *
 * <p><b>职责</b>：注册三种 {@link io.micrometer.context.ThreadLocalAccessor} + 开启 Reactor 自动传播：</p>
 * <ul>
 *   <li>{@link Slf4jThreadLocalAccessor}（零参 = Global MDC）：传播整张 MDC（traceId/spanId/step/step_id/llm_* 等）。</li>
 *   <li>{@link OpenTelemetryContextAccessor}：传播 OTel Context，使子线程新开 step span 挂父 trace 下。</li>
 *   <li>{@link ConversationContextAccessor}：传播对话上下文，让流式回调线程能取到当前对话 trace 写 output。</li>
 * </ul>
 *
 * <p><b>协作</b>：由 {@code TelemetryAutoConfiguration} 注册为 bean，{@link #afterPropertiesSet()} 在 bean 初始化时执行，
 * 随后开启 {@link Hooks#enableAutomaticContextPropagation()}，使 {@code stream()} 的 Flux 在回调线程自动恢复 MDC + OTel Context + 对话上下文。</p>
 *
 * <p><b>不做什么</b>：不管 step span 生命周期；必须在任何 Flux/Mono subscribe 前执行（bean 初始化期，早于业务）。</p>
 */
@Slf4j
public class ContextPropagationConfiguration implements InitializingBean {

    @Override
    public void afterPropertiesSet() {
        ContextRegistry registry = ContextRegistry.getInstance();
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
        registry.registerThreadLocalAccessor(new OpenTelemetryContextAccessor());
        registry.registerThreadLocalAccessor(new ConversationContextAccessor());
        Hooks.enableAutomaticContextPropagation();
        log.info("[ContextPropagation] accessors 已注册(MDC + OTel + Conversation), Reactor 自动传播 enabled={}",
                Hooks.isAutomaticContextPropagationEnabled());
    }
}
