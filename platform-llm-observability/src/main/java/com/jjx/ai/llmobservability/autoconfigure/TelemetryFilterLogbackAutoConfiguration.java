package com.jjx.ai.llmobservability.autoconfigure;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilterChain;
import com.jjx.ai.llmobservability.observation.filter.TelemetryLogbackFilter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 把同一过滤器链装到所有 logback appender 之前，覆盖普通日志（框架/第三方日志）的整条生命周期。
 *
 * <p><b>开关</b>：{@code telemetry.filter.logback-enabled=false} 可关闭；未注册任何
 * {@code TelemetryFilter} bean 时链为空，装不装都不改变行为。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(TelemetryAutoConfiguration.class)
@ConditionalOnClass(LoggerContext.class)
@ConditionalOnProperty(prefix = "telemetry.filter", name = {"enabled", "logback-enabled"},
        havingValue = "true", matchIfMissing = true)
public class TelemetryFilterLogbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TelemetryLogbackFilterInstaller telemetryLogbackFilterInstaller(TelemetryFilterChain chain) {
        return new TelemetryLogbackFilterInstaller(chain);
    }

    static class TelemetryLogbackFilterInstaller implements InitializingBean {

        private final TelemetryFilterChain chain;

        TelemetryLogbackFilterInstaller(TelemetryFilterChain chain) {
            this.chain = chain;
        }

        @Override
        public void afterPropertiesSet() {
            try {
                if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
                    return;
                }
                TelemetryLogbackFilter filter = new TelemetryLogbackFilter(chain);
                Set<Appender<ILoggingEvent>> seen =
                        Collections.newSetFromMap(new IdentityHashMap<>());
                for (ch.qos.logback.classic.Logger logger : context.getLoggerList()) {
                    for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders();
                         it.hasNext(); ) {
                        Appender<ILoggingEvent> appender = it.next();
                        if (seen.add(appender)) {
                            appender.addFilter(filter);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 过滤器安装失败不影响业务与既有日志
            }
        }
    }
}
