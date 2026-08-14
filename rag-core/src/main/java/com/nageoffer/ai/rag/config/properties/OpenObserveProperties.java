package com.nageoffer.ai.rag.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OpenObserve 查询配置（日志诊断「读取侧」）。
 *
 * <p>与 logback-spring.xml 里 {@code OpenObserveAppender}（「写入侧」）的值保持一致；
 * 因 Java 侧拿不到 logback 注入值，诊断查询独立绑定本配置。
 *
 * <p><b>stream 名坑</b>：appender 配置写 {@code springai-rag_logs}（连字符），
 * OpenObserve 收录时把连字符规范化为下划线，实际存储 stream 为 {@code springai_rag_logs}，
 * 查询必须用下划线名（否则查不到）。
 */
@Data
@Configuration
@ConfigurationProperties("obs.openobserve")
public class OpenObserveProperties {

    /** API 根地址（到组织），默认本地 default 组织 */
    private String url = "http://localhost:5080/api/default";

    /** Web UI 根地址（浏览器可达，前端链路追踪页跳转用；与 url 的 API 地址区分，生产用 OPENOBSERVE_WEB_URL 覆盖） */
    private String webUrl = "http://localhost:5080";

    /** 日志 stream 名（OpenObserve 实际存储名，下划线） */
    private String stream = "springai_rag_logs";

    /** Basic Auth 用户名 */
    private String username = "admin@openobserve.io";

    /** Basic Auth 密码（生产环境用环境变量 OO_PASSWORD 覆盖：application.yaml 里 openobserve.password: ${OO_PASSWORD}） */
    private String password = "OpenObserve@2026";

    /** 单次查询日志上限 */
    private int maxLogs = 200;

    /** 查询时间窗口（天）：end=now 向前回溯 */
    private int lookbackDays = 7;
}
