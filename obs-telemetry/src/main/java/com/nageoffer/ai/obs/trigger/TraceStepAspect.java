package com.nageoffer.ai.obs.trigger;

import com.nageoffer.ai.obs.Telemetry;
import com.nageoffer.ai.obs.TraceStep;
import com.nageoffer.ai.obs.event.TraceHandle;
import com.nageoffer.ai.obs.processor.Summarizer;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.publisher.Flux;

/**
 * {@link TraceStep} 注解切面：自动为标注方法开 span、摘要输入输出、记录耗时与异常，按返回类型自动分派生命周期。
 *
 * <p><b>所属维度</b>：①触发（只调度 {@link TraceHandle} 生命周期，不直接落地）。</p>
 *
 * <p><b>职责</b>：方法体无需改动即获得埋点。按返回类型分派：普通对象→同步 output+close；{@link Flux}→装饰 finish；{@link ResponseBodyEmitter}/{@code SseEmitter}→回调 finish。</p>
 *
 * <p><b>协作</b>：span 名 = {@code @TraceStep.value()}（若 target 有 {@code getName()} 则展开为 {@code value.getName}）；{@code kind=ROOT} 走 {@link Telemetry#openTrace}，默认 STEP 走 {@link Telemetry#openStep}；流式经 {@link Telemetry#decorateFlux}。</p>
 *
 * <p><b>不做什么</b>：不直接写 span/发日志（经 {@link TraceHandle} → sink 链）；仅对 Spring 代理的 bean 方法生效。</p>
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceStepAspect {

    private final Telemetry telemetry;

    public TraceStepAspect(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around("@annotation(com.nageoffer.ai.obs.TraceStep)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        TraceStep traceStep = signature.getMethod().getAnnotation(TraceStep.class);
        String name = resolveName(traceStep.value(), pjp.getTarget());

        TraceHandle handle = traceStep.kind() == TraceStep.Kind.ROOT
                ? telemetry.openTrace(name)
                : telemetry.openStep(name);
        handle.input(Summarizer.summarizeArgs(pjp.getArgs(), paramNamesOf(signature.getMethod())));

        try {
            Object ret = pjp.proceed();

            if (ret instanceof Flux<?> flux) {
                return telemetry.decorateFlux(handle, flux, traceStep.captureOutput());
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

    private void registerEmitterCallbacks(ResponseBodyEmitter emitter, TraceHandle handle) {
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
        } catch (NoSuchMethodException ignored) {
            log.info("No getName method found on target class {}", target.getClass().getName());
            log.debug("Exception occurred while invoking getName method", ignored);
        } catch (Exception ignored) {
            log.debug("Exception occurred while invoking getName method", ignored);

        }
        return prefix;
    }
}
