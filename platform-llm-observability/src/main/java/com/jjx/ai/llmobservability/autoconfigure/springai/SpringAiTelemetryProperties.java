package com.jjx.ai.llmobservability.autoconfigure.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * telemetry 的 Spring AI 内容捕获开关（前缀 {@code telemetry.springai}）。
 *
 * <p>Spring AI 默认不输出 prompt/completion（隐私原因），需要排查时由宿主打开，
 * 本属性经 {@link SpringAiTelemetryEnvironmentPostProcessor} 映射到 Spring AI 原生配置。</p>
 */
@ConfigurationProperties("telemetry.springai")
public class SpringAiTelemetryProperties {

    /** 映射 spring.ai.chat.observations.log-prompt。 */
    private boolean logPrompt = false;

    /** 映射 spring.ai.chat.observations.log-completion。 */
    private boolean logCompletion = false;

    public boolean isLogPrompt() {
        return logPrompt;
    }

    public void setLogPrompt(boolean logPrompt) {
        this.logPrompt = logPrompt;
    }

    public boolean isLogCompletion() {
        return logCompletion;
    }

    public void setLogCompletion(boolean logCompletion) {
        this.logCompletion = logCompletion;
    }
}
