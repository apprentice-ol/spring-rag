package com.nageoffer.ai.rag.config.telemetry;

import com.nageoffer.ai.llmobservability.observation.TelemetryTemplate;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.intent.IntentResult;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * trace 维度提取器注册（启动期一次性）。
 *
 * <p><b>设计</b>：维度不在业务管道里"申报"，而是观测层从 step 返回值<b>自动提取</b>——意图分类的返回值
 * 就是意图、agent 编排的返回值里就带范式。本类是唯一知道"哪个类型贡献哪些维度"的地方；
 * 类字面量 + 方法引用保证领域对象重构时这里编译报错，不会静默失效。</p>
 *
 * <p>触发点：被 {@code @TelemetryStep} 标注的方法（或 {@code TelemetryTemplate.step}）返回时，
 * telemetry 按{@link TelemetryTemplate#dimensionOnOutput}注册表匹配类型并提取。</p>
 */
@Configuration
public class TelemetryDimensions {

    public TelemetryDimensions(TelemetryTemplate obsTemplate) {
        // 意图维度：rag.intent.classify 的返回值
        obsTemplate.dimensionOnOutput(IntentResult.class,
                r -> Map.of("intent", r.getIntent()));
        // agent 范式维度：rag.agent.plan 的返回值（AgentTrace 自带 paradigm）
        obsTemplate.dimensionOnOutput(AgentRetrievalResult.class,
                r -> r.trace() != null ? Map.of("agent", r.trace().getParadigm()) : null);
    }
}
