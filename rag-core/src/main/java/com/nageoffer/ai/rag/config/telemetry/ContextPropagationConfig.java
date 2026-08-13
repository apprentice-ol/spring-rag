package com.nageoffer.ai.rag.config.telemetry;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Micrometer Context Propagation 装配（替代手写 {@code ContextPropagator} / {@code TelemetryTaskDecorator}）。
 *
 * <p>注册两个 {@link io.micrometer.context.ThreadLocalAccessor} 到全局 {@link ContextRegistry}——其静态块已
 * 经 ServiceLoader 自动加载了 micrometer-observation 的 {@code ObservationThreadLocalAccessor}，此处在其基础上追加：
 * <ul>
 *   <li>{@link Slf4jThreadLocalAccessor}（零参 = Global MDC）：传播整张 MDC
 *       （traceId/spanId/rag_step/step_id/conversation_id/llm_* 等）——替代 {@code MDC.getCopyOfContextMap()}；</li>
 *   <li>{@link OpenTelemetryContextThreadLocalAccessor}：传播 OTel Context——替代 {@code Context.current()} 捕获，
 *       使跨线程新开的 step span 挂在父 trace 下。</li>
 * </ul>
 *
 * <p>随后开启 Reactor 全局自动传播 {@link Hooks#enableAutomaticContextPropagation()}，使
 * {@code ChatClient.stream()} 的 Flux 在 onNext/onError/onComplete/doFinally 回调线程（LLM HTTP 客户端线程）
 * 自动恢复 MDC + OTel Context + 当前 Observation，从而 {@code StepSpan.finish()} 的手写 mdcSnapshot 恢复
 * 可在阶段 2 安全简化。
 *
 * <p>必须在任何 Flux/Mono subscribe 前调用——{@code @PostConstruct} 于上下文刷新期完成，早于请求服务。
 */
@Slf4j
@Configuration
public class ContextPropagationConfig {

    @PostConstruct
    void registerAndEnable() {
        ContextRegistry registry = ContextRegistry.getInstance();
        // Global MDC：零参（空可变参数）= 传播整张 MDC map
        registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
        // OTel Context：保证子线程/池线程/回调线程里新开的 step span 挂在父 trace 下
        registry.registerThreadLocalAccessor(new OpenTelemetryContextThreadLocalAccessor());
        // ConversationTrace ambient 作用域：让 saveMessage 在虚拟线程/Reactor 回调里取到当前对话 trace（业务无感）
        registry.registerThreadLocalAccessor(new ConversationTraceAccessor());
        // Reactor Flux 跨线程自动恢复上下文（MDC + OTel + Observation）
        Hooks.enableAutomaticContextPropagation();
        log.info("[ContextPropagation] accessors 已注册(MDC + OTel), Reactor 自动传播 enabled={}",
                Hooks.isAutomaticContextPropagationEnabled());
    }
}
