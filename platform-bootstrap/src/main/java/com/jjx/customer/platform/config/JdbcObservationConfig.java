package com.jjx.customer.platform.config;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import net.ttddyy.observation.boot.autoconfigure.ProxyDataSourceBuilderCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JDBC 观测的官方扩展点装配（2026-10-12 依据 datasource-micrometer 官方文档重构）。
 *
 * <p>OTel 语义（db.system / db.statement / db.operation，经 JSqlParser 分析归一化）由官方
 * {@code datasource-micrometer-opentelemetry} 模块自动产出——模块在 classpath 即被
 * Spring Boot 自动配置（官方文档 §4.5："No additional manual configuration is required
 * beyond adding the module to the classpath"）。本类只保留两个官方文档明示的定制点：</p>
 *
 * <ul>
 *   <li><b>{@link ProxyDataSourceBuilderCustomizer}</b>（官方文档 §5.2.7 "How to Customize the
 *       ProxyDataSource Creation"）：datasource 名即 span 的 remote service name（§3.2.3），
 *       也就是 trace 视图中数据库节点的显示名——用官方定制点改名，而非事后覆盖 span 属性。</li>
 *   <li><b>{@link ObservationPredicate}</b>（官方文档 §5.2.10 的选择性机制）：无激活 trace
 *       上下文的 JDBC observation（应用启动期建表校验、无请求上下文的异步 SQL）不产生 span——
 *       否则它们以独立迷你 trace 在 Traces 列表刷屏（2026-10-12 实测 532 条 "query" 条目）。</li>
 * </ul>
 *
 * <p>被本类取代的历史实现（同日重构）：手写 jdbc.*→db.* 翻译 filter（官方 OTel 模块接管）、
 * SpanExportingPredicate 层的事后过滤（includes=QUERY 从源头控制 + 本判定上移到 observation 层）。</p>
 */
@Configuration
public class JdbcObservationConfig {

    private static final Logger log = LoggerFactory.getLogger(JdbcObservationConfig.class);

    /** 数据库节点显示名（remote service name；2026-10-12 用户拍板的自解释形态）。 */
    private static final String DATASOURCE_DISPLAY_NAME = "PostgreSQL/springai_rag";

    @Bean
    public ProxyDataSourceBuilderCustomizer datasourceDisplayNameCustomizer() {
        return (builder, dataSource, beanName, dataSourceName) -> {
            builder.name(DATASOURCE_DISPLAY_NAME);
            log.info("[JdbcObs] datasource 显示名 -> {}（bean={}, 原解析名={}）",
                    DATASOURCE_DISPLAY_NAME, beanName, dataSourceName);
        };
    }

    /**
     * 无 trace 上下文的 JDBC observation 不产生 span。
     *
     * <p>判定用 micrometer {@link Tracer#currentSpan()}：observation 启动时外层若无激活 span
     * （启动期 schema 初始化、无请求上下文的异步任务），该 SQL 不属于任何调用链，产生与否只增噪。
     * 对话/请求内的 SQL 始终有业务 span 在上下文中，不受影响。</p>
     */
    @Bean
    public ObservationPredicate jdbcRequiresActiveTracePredicate(Tracer tracer) {
        return (name, context) -> {
            if (name != null && name.startsWith("jdbc.")) {
                return tracer.currentSpan() != null;
            }
            return true;
        };
    }
}
