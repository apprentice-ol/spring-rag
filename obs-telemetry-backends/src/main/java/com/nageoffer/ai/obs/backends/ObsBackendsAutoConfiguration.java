package com.nageoffer.ai.obs.backends;

import com.nageoffer.ai.obs.backends.langfuse.LangfuseProperties;
import com.nageoffer.ai.obs.backends.openobserve.OpenObserveProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * obs-telemetry-backends 的统一自动装配入口。
 *
 * <p>这里只负责把后端配置类注册为 Spring Bean，保证无论 collector 是否开启，
 * 业务侧都可以注入 {@code OpenObserveProperties} / {@code LangfuseProperties}。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({OpenObserveProperties.class, LangfuseProperties.class})
public class ObsBackendsAutoConfiguration {
}
