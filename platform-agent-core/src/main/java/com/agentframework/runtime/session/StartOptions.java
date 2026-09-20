package com.agentframework.runtime.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话启动参数：调用方在会话开始前可以决定的一切。
 *
 * @param sessionId 指定会话 id，为空则自动生成
 * @param tenantId  租户 id
 * @param userId    用户 id
 * @param workspaceId 复用已有工作区 id，为空则新建
 * @param traceId   指定链路追踪 id，为空则新建
 * @param slots     初始槽位值
 * @param attributes 自定义属性，透传给守卫 / 过滤器 / 拦截器
 * @param ephemeral 是否为临时会话（不落库，用于评测与测试）
 */
public record StartOptions(
        String sessionId,
        String tenantId,
        String userId,
        String workspaceId,
        String traceId,
        Map<String, Object> slots,
        Map<String, Object> attributes,
        boolean ephemeral) {

    public StartOptions {
        slots = slots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** @return 全默认参数的选项 */
    public static StartOptions defaults() {
        return new StartOptions(null, "default", "anonymous", null, null, null, null, false);
    }

    /**
     * @param tenantId 租户 id
     * @param userId   用户 id
     * @return 指定租户与用户的选项
     */
    public static StartOptions of(String tenantId, String userId) {
        return new StartOptions(null, tenantId, userId, null, null, null, null, false);
    }

    /** @return 流式构建器 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param name  槽位名
     * @param value 槽位值
     * @return 追加初始槽位后的选项
     */
    public StartOptions withSlot(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(slots);
        merged.put(name, value);
        return new StartOptions(sessionId, tenantId, userId, workspaceId, traceId, merged, attributes, ephemeral);
    }

    /**
     * @param name  属性名
     * @param value 属性值
     * @return 追加属性后的选项
     */
    public StartOptions withAttribute(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(name, value);
        return new StartOptions(sessionId, tenantId, userId, workspaceId, traceId, slots, merged, ephemeral);
    }

    /**
     * @param sessionId 会话 id
     * @return 指定会话 id 后的选项
     */
    public StartOptions withSessionId(String sessionId) {
        return new StartOptions(sessionId, tenantId, userId, workspaceId, traceId, slots, attributes, ephemeral);
    }

    /**
     * @param newTraceId 链路追踪 id（空白则忽略，仍由引擎生成——调用方拿不到业务链 id 时不必判空）
     * @return 指定链路 id 后的选项。会话 traceId 会流向引擎持久化记录（SessionRecord）、
     *         引擎事件与根 span，是「引擎会话 ↔ 业务链」对齐的唯一入口
     */
    public StartOptions withTraceId(String newTraceId) {
        return newTraceId == null || newTraceId.isBlank()
                ? this
                : new StartOptions(sessionId, tenantId, userId, workspaceId, newTraceId, slots, attributes, ephemeral);
    }

    /** @return 标记为临时会话后的选项（不落库） */
    public StartOptions asEphemeral() {
        return new StartOptions(sessionId, tenantId, userId, workspaceId, traceId, slots, attributes, true);
    }

    /** @return 归一化后的租户 id */
    public String effectiveTenantId() {
        return tenantId == null ? "default" : tenantId;
    }

    /** @return 归一化后的用户 id */
    public String effectiveUserId() {
        return userId == null ? "anonymous" : userId;
    }

    /** {@link StartOptions} 的流式构建器。 */
    public static final class Builder {

        private String sessionId;
        private String tenantId = "default";
        private String userId = "anonymous";
        private String workspaceId;
        private String traceId;
        private final Map<String, Object> slots = new LinkedHashMap<>();
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private boolean ephemeral;

        /**
         * @param sessionId 会话 id
         * @return 当前构建器
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * @param tenantId 租户 id
         * @return 当前构建器
         */
        public Builder tenant(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /**
         * @param userId 用户 id
         * @return 当前构建器
         */
        public Builder user(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * @param workspaceId 复用的工作区 id
         * @return 当前构建器
         */
        public Builder workspace(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        /**
         * @param traceId 链路追踪 id
         * @return 当前构建器
         */
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * @param name  槽位名
         * @param value 槽位值
         * @return 当前构建器
         */
        public Builder slot(String name, Object value) {
            this.slots.put(name, value);
            return this;
        }

        /**
         * @param name  属性名
         * @param value 属性值
         * @return 当前构建器
         */
        public Builder attribute(String name, Object value) {
            this.attributes.put(name, value);
            return this;
        }

        /**
         * @param ephemeral 是否临时会话
         * @return 当前构建器
         */
        public Builder ephemeral(boolean ephemeral) {
            this.ephemeral = ephemeral;
            return this;
        }

        /** @return 构建完成的启动参数 */
        public StartOptions build() {
            return new StartOptions(sessionId, tenantId, userId, workspaceId, traceId, slots, attributes, ephemeral);
        }
    }
}
