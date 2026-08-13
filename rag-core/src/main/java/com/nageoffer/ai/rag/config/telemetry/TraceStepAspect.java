package com.nageoffer.ai.rag.config.telemetry;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
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
 * {@link TraceStep} 注解切面：自动为标注方法开 span、摘要输入输出、记录耗时与异常，并按<b>返回类型</b>自动
 * 分派生命周期，方法体无需任何改动：
 *
 * <ul>
 *   <li>普通对象 → 同步 {@code output + close}</li>
 *   <li>{@link Flux} → 装饰（doOnNext 累积 + doOnError error + doFinally finish），返回装饰后 Flux</li>
 *   <li>{@link ResponseBodyEmitter}/{@code SseEmitter} → 注册完成回调 finish，返回 emitter（不 close）</li>
 * </ul>
 *
 * <p>span 名 = {@code @TraceStep.value()}；若目标 bean 暴露 {@code getName()}（channel / postprocessor），
 * 自动展开为 {@code value + "." + getName()}。</p>
 *
 * <p>{@link TraceStep.Kind#ROOT} 走 {@link RagTelemetry#startRoot}（无父新 trace），默认 STEP 走
 * {@link RagTelemetry#step}（挂当前父）。</p>
 *
 * <p>仅对 Spring 代理的 bean 方法生效；静态方法 / 私有方法 / 同类内部调用切不到（用手动 {@link RagTelemetry}）。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceStepAspect {

    private final RagTelemetry telemetry;

    public TraceStepAspect(RagTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Around("@annotation(com.nageoffer.ai.rag.config.telemetry.TraceStep)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        TraceStep ts = signature.getMethod().getAnnotation(TraceStep.class);
        String name = resolveName(ts.value(), pjp.getTarget());

        StepSpan span = ts.kind() == TraceStep.Kind.ROOT
                ? telemetry.startRoot(name)
                : telemetry.step(name);
        span.input(Summarizer.summarizeArgs(pjp.getArgs(), paramNamesOf(signature.getMethod())));

        try {
            Object ret = pjp.proceed();

            if (ret instanceof Flux<?> flux) {
                // 流式：装饰 Flux，span 在流终止时 finish；output 按 captureOutput 决定是否完整捕获
                return telemetry.decorateFlux(span, flux, ts.captureOutput());
            }
            if (ret instanceof ResponseBodyEmitter emitter) {
                // 流式（SSE）：span 在 emitter 完成/超时/异常回调时 finish，方法返回时不 close
                registerEmitterCallbacks(emitter, span);
                span.closeScope();
                return emitter;
            }

            // 同步：记 output 并 close
            span.output(ret);
            span.close();
            return ret;
        } catch (Throwable e) {
            // 同步异常路径：记 error 并 close；流式路径 proceed 返回的是惰性 Flux，异常在 subscribe 时由 doOnError 处理
            span.error(e);
            span.close();
            throw e;
        }
    }

    /** 把 emitter 的完成/超时/异常回调都接到 span.finish()（幂等，首个生效）。 */
    private void registerEmitterCallbacks(ResponseBodyEmitter emitter, StepSpan span) {
        try {
            emitter.onCompletion(span::finish);
            emitter.onTimeout(span::finish);
            emitter.onError(e -> span.finish());
        } catch (Exception ignored) {
            // emitter 已 complete，无法注册回调：兜底立即 finish
            span.finish();
        }
    }

    /** 提取方法参数名（需编译带 -parameters；否则 getName() 返回 arg0 自动降级）。 */
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

    /** 若 target 有无参 getName()，拼成 {@code prefix.getName}；否则用 prefix。 */
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
            // 无 getName()，用 prefix
        } catch (Exception ignored) {
            // 反射失败，降级用 prefix
        }
        return prefix;
    }
}
