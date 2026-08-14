package com.nageoffer.ai.obs.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * obs 的 Spring AI 内容捕获开关（前缀 {@code obs.springai}）。
 *
 * <p>Spring AI 默认不输出 prompt/completion（隐私原因），需要排查时由宿主打开，
 * 本属性经 {@link SpringAiObsEnvironmentPostProcessor} 映射到 Spring AI 原生配置。</p>
 */
@ConfigurationProperties("obs.springai")
public class SpringAiObsProperties {

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
