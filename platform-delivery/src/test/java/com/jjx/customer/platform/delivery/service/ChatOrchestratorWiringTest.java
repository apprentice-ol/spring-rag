package com.jjx.customer.platform.delivery.service;

import com.jjx.customer.platform.business.orchestration.ChatOrchestrator;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.delivery.sse.SseDeliveryPortFactory;
import com.jjx.customer.platform.delivery.service.impl.DefaultChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Constructor;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * 装配契约：编排器（business）以泛型 SPI 依赖交付端口工厂，Spring 必须能把
 * {@code DeliveryPortFactory<SseEmitter>} 解析到 delivery 的 {@code SseDeliveryPortFactory}。
 *
 * <p>用最小上下文验证（不启动数据库/Redis/Web）：交互方全部 mock，只验证泛型注入可解析、
 * 且注入的确实是 SSE 端口工厂。若注入点与实现失配，本用例失败而不是线上启动失败。</p>
 */
class ChatOrchestratorWiringTest {

    /** 与 {@code DefaultChatService} 相同的注入点：按 SseEmitter 范式取编排器。 */
    static class SseEmitterDriver {
        final ChatOrchestrator<SseEmitter> orchestrator;

        SseEmitterDriver(ChatOrchestrator<SseEmitter> orchestrator) {
            this.orchestrator = orchestrator;
        }
    }

    @Test
    void 编排器按SseEmitter范式解析到SSE交付端口工厂() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ConfigurableListableBeanFactory beanFactory = ctx.getBeanFactory();
            // 编排器的其余协作方与本次装配无关，全部以 mock 顶替（真实实现由各自模块单测覆盖）
            for (Class<?> type : collaboratorTypes()) {
                beanFactory.registerSingleton(type.getSimpleName(), mock(type));
            }
            // 唯一带限定符的协作方（@Qualifier("chatPersistExecutor") 的流式落库线程池）
            beanFactory.registerSingleton("chatPersistExecutor", mock(ExecutorService.class));
            Object ssePortFactory = mock(SseDeliveryPortFactory.class);
            beanFactory.registerSingleton("sseDeliveryPortFactory", ssePortFactory);
            ctx.register(ChatOrchestrator.class, DefaultChatService.class, SseEmitterDriver.class);
            ctx.refresh();

            ChatOrchestrator<SseEmitter> orchestrator = ctx.getBean(SseEmitterDriver.class).orchestrator;
            assertNotNull(orchestrator, "编排器应能按 SseEmitter 范式装配");
            assertSame(ssePortFactory, ReflectionTestUtils.getField(orchestrator, "deliveryPortFactory"),
                    "注入的必须是 SSE 交付端口工厂（而非其它通道实现）");

            // 生产注入点：delivery 入口服务同样必须装配成功
            assertNotNull(ctx.getBean(DefaultChatService.class));
        }
    }

    /** 编排器构造器上除交付端口工厂外的全部协作类型（去重）。 */
    private static Set<Class<?>> collaboratorTypes() {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Constructor<?> ctor : ChatOrchestrator.class.getConstructors()) {
            for (Class<?> type : ctor.getParameterTypes()) {
                if (DeliveryPortFactory.class.isAssignableFrom(type) || ExecutorService.class.equals(type)) {
                    continue;
                }
                types.add(type);
            }
        }
        return types;
    }
}
