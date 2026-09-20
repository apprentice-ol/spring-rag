package com.jjx.customer.platform.business.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ops 诊断链路配置（prefix {@code ops}）。
 *
 * @param openobserve 日志查询（OpenObserve）
 * @param logsCache   日志查询缓存（两档 TTL）
 * @param sessionTtlMinutes 追问会话 TTL（分钟，基准最近更新时间）
 * @param maxLlmCalls 流程级 LLM 调用数上限（O9：超限转升级，不静默截断；D9：显式声明，参考实现未声明等于不限）
 * @param qaMaxPerTask 任务内直答（Task QA）配额：单任务成功直答次数上限，超限回固定文案
 *                     （P1：不降级重跑——重跑更贵，降级正中滥用）
 */
@ConfigurationProperties(prefix = "ops")
public record OpsProperties(
        OpenObserve openobserve,
        LogsCache logsCache,
        Integer sessionTtlMinutes,
        Integer maxLlmCalls,
        Integer qaMaxPerTask) {

    /** @return OpenObserve 配置，缺省为空配置（字段名取 OTel 语义约定） */
    public OpenObserve openobserveOrDefault() {
        return openobserve == null
                ? new OpenObserve(null, null, null, null, "default", 7, 30, true, 10, "body", "severity",
                        "trace_id", "service_name")
                : openobserve;
    }

    /** @return 缓存配置，缺省开启（trace 24h / 时间窗 60s） */
    public LogsCache logsCacheOrDefault() {
        return logsCache == null ? new LogsCache(true, 60, 1440) : logsCache;
    }

    /** @return 会话 TTL（分钟），缺省 60（对齐参考实现） */
    public long sessionTtlMinutesEffective() {
        return sessionTtlMinutes == null || sessionTtlMinutes <= 0 ? 60 : sessionTtlMinutes;
    }

    /** @return LLM 调用上限，缺省 24（三阶段 maxSteps 4+5+3 + 抽槽/裁决余量） */
    public int maxLlmCallsEffective() {
        return maxLlmCalls == null || maxLlmCalls <= 0 ? 24 : maxLlmCalls;
    }

    /** @return 任务内直答配额，缺省 10（≤0 也取缺省——配额是防长尾不是开关，关闭走负数不成立） */
    public int qaMaxPerTaskEffective() {
        return qaMaxPerTask == null || qaMaxPerTask <= 0 ? 10 : qaMaxPerTask;
    }

    /**
     * OpenObserve 连接与查询参数。
     *
     * <p>字段名可配：真实 OTel 日志流里两套命名并存（OTel 语义约定 {@code body/severity/...}
     * 与旧命名 {@code message/level/...}），但**通常只有一套有数据**；SQL 里引用存在但全空的列
     * 不会报错、只会静默查不到，因此字段名默认取真实观测到的 OTel 那套。</p>
     *
     * @param url          API 根地址（含组织，如 http://localhost:5080/api/default）
     * @param username     账号（Basic auth）
     * @param password     密码
     * @param stream       日志流名
     * @param traceStream  链路流名（本次未用，保留对齐）
     * @param lookbackDays traceId 精查回溯天数
     * @param maxLogs      单次返回上限
     * @param queryEnabled 是否启用查询
     * @param timeoutSeconds 请求超时（秒）
     * @param messageField 正文列名（默认 body，OTel 语义约定）
     * @param levelField   级别列名（默认 severity）
     * @param traceIdField 链路列名（默认 trace_id）
     * @param serviceField 服务列名（默认 service_name）
     */
    public record OpenObserve(
            String url,
            String username,
            String password,
            String stream,
            String traceStream,
            int lookbackDays,
            int maxLogs,
            boolean queryEnabled,
            int timeoutSeconds,
            String messageField,
            String levelField,
            String traceIdField,
            String serviceField) implements com.jjx.customer.platform.observe.openobserve.OpenObserveEndpoint {

        /** @return 正文列名，缺省 body */
        public String messageFieldOrDefault() {
            return messageField == null || messageField.isBlank() ? "body" : messageField;
        }

        /** @return 级别列名，缺省 severity */
        public String levelFieldOrDefault() {
            return levelField == null || levelField.isBlank() ? "severity" : levelField;
        }

        /** @return 链路列名，缺省 trace_id */
        public String traceIdFieldOrDefault() {
            return traceIdField == null || traceIdField.isBlank() ? "trace_id" : traceIdField;
        }

        /** @return 服务列名，缺省 service_name */
        public String serviceFieldOrDefault() {
            return serviceField == null || serviceField.isBlank() ? "service_name" : serviceField;
        }
    }

    /**
     * 日志查询缓存。
     *
     * @param enabled          总开关
     * @param windowTtlSeconds 时间窗模糊查 TTL（秒，end 落分钟桶与 60s 对齐）
     * @param traceTtlMinutes  traceId 精查 TTL（分钟，历史日志不可变可长存）
     */
    public record LogsCache(boolean enabled, long windowTtlSeconds, long traceTtlMinutes) {
    }
}
