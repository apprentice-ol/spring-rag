package com.nageoffer.ai.obs.observation.aspect;

import com.nageoffer.ai.obs.observation.ObsTemplate;
import com.nageoffer.ai.obs.observation.annotation.ObservedStep;
import com.nageoffer.ai.obs.observation.span.ObsSpan;
import com.nageoffer.ai.obs.observation.support.Summarizer;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

/**
 * {@link ObservedStep} 注解切面：自动为标注方法开 span、摘要输入输出、记录耗时与异常，按返回类型自动分派生命周期。
 *
 * <p><b>所属维度</b>：①触发（只调度 {@link ObsSpan} 生命周期，不直接落地）。</p>
 *
 * <p><b>职责</b>：方法体无需改动即获得埋点。按返回类型分派：普通对象→同步 output+close；{@link Flux}→延迟到首次订阅开 span + finish；{@link ResponseBodyEmitter}/{@code SseEmitter}→回调 finish。</p>
 *
 * <p><b>协作</b>：由 {@code ObsAutoConfiguration} 注册为 bean。span 名 = {@code @ObservedStep.value()}（若 target 有 {@code getName()} 则展开为 {@code value.getName}）；{@code kind=ROOT} 走 {@link ObsTemplate#openTrace}，默认 STEP 走 {@link ObsTemplate#openStep}；流式经 {@link ObsTemplate#decorateFlux}/{@link ObsTemplate#deferStep}。</p>
 *
 * <p><b>不做什么</b>：不直接写 span/发日志（经 {@link ObsSpan} → sink 链）；仅对 Spring 代理的 bean 方法生效。</p>
 */
@Slf4j
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ObservedStepAspect {

    private final ObsTemplate obsTemplate;

    public ObservedStepAspect(ObsTemplate obsTemplate) {
        this.obsTemplate = obsTemplate;
    }

    @Around("@annotation(com.nageoffer.ai.obs.observation.annotation.ObservedStep)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        ObservedStep traceStep = signature.getMethod().getAnnotation(ObservedStep.class);
        String name = resolveName(traceStep.value(), pjp.getTarget());
        Object input = Summarizer.summarizeArgs(pjp.getArgs(), paramNamesOf(signature.getMethod()));

        // Flux 返回值且默认 STEP：延迟到首次订阅时再开 span——被分支短路/从未订阅的 Flux 不留悬空
        // span/trace，重复订阅每次各开一个。ROOT + Flux（后台任务脱钩）保持立即开。
        if (traceStep.kind() == ObservedStep.Kind.STEP
                && Flux.class.isAssignableFrom(signature.getReturnType())) {
            Object ret;
            try {
                ret = pjp.proceed();
            } catch (Throwable e) {
                try (ObsSpan h = obsTemplate.openStep(name)) {
                    h.input(input);
                    h.error(e);
                }
                throw e;
            }
            if (ret instanceof Flux<?> flux) {
                return obsTemplate.deferStep(name, input, () -> flux, traceStep.captureOutput());
            }
            // 声明 Flux 但实际返回 null/其他：降级为同步 step 记录
            try (ObsSpan h = obsTemplate.openStep(name)) {
                h.input(input);
                h.output(ret);
            }
            return ret;
        }

        ObsSpan handle = traceStep.kind() == ObservedStep.Kind.ROOT
                ? obsTemplate.openTrace(name)
                : obsTemplate.openStep(name);
        handle.input(input);

        try {
            Object ret = pjp.proceed();

            if (ret instanceof Flux<?> flux) {
                return obsTemplate.decorateFlux(handle, flux, traceStep.captureOutput());
            }
            if (ret instanceof ResponseBodyEmitter emitter) {
                registerEmitterCallbacks(emitter, handle);
                handle.closeScope();
                return emitter;
            }

            handle.output(ret);
            handle.close();
            return ret;
        } catch (Throwable e) {
            handle.error(e);
            handle.close();
            throw e;
        }
    }

    private void registerEmitterCallbacks(ResponseBodyEmitter emitter, ObsSpan handle) {
        try {
            emitter.onCompletion(handle::finish);
            emitter.onTimeout(handle::finish);
            emitter.onError(e -> handle.finish());
        } catch (Exception ignored) {
            handle.finish();
        }
    }

    private String[] paramNamesOf(Method method) {
        try {
            Parameter[] params = method.getParameters();
            String[] names = new String[params.length];
            for (int i = 0; i < params.length; i++) {
                names[i] = params[i].getName();
            }
            return names;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveName(String prefix, Object target) {
        if (target == null) {
            return prefix;
        }
        try {
            Method getName = target.getClass().getMethod("getName");
            Object n = getName.invoke(target);
            if (n != null && !n.toString().isBlank()) {
                return prefix + "." + n;
            }
        } catch (Exception e) {
            // 绝大多数目标类没有 getName()（属正常路径），仅 debug 记录，绝不逐调用刷 INFO
            log.debug("resolveName fallback to prefix on {}", target.getClass().getName(), e);
        }
        return prefix;
    }
}
