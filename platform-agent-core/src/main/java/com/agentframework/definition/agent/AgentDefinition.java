package com.agentframework.definition.agent;

import com.agentframework.definition.policy.AgentPolicies;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 定义：可版本化的声明式资产，本身不持有任何运行态。
 *
 * <p>定义与实例严格分离——定义在这里，Session / Workspace / Slot 由引擎在运行时创建。</p>
 *
 * @param id                Agent 唯一标识
 * @param version           版本号，缺省为 {@link #LATEST}
 * @param workflowId        绑定的 Workflow 定义 id
 * @param workflowVersion   绑定的 Workflow 版本，缺省为 {@link #LATEST}
 * @param promptProfile     Prompt 配置档名，缺省与 Workflow 同名
 * @param policies          工具 / 守卫 / 过滤器 / 拦截器 / 配额策略集合
 * @param modelConfig       默认模型配置，节点可通过 modelOverride 覆盖
 * @param workspaceTemplate 默认工作区模板名
 * @param metadata          业务侧自定义元数据
 */
public record AgentDefinition(
        String id,
        String version,
        String workflowId,
        String workflowVersion,
        String promptProfile,
        AgentPolicies policies,
        ModelConfig modelConfig,
        String workspaceTemplate,
        Map<String, Object> metadata) {

    public static final String LATEST = "latest";

    public AgentDefinition {
        Objects.requireNonNull(id, "agent id is required");
        if (id.isBlank()) {
            throw new IllegalArgumentException("agent id must not be blank");
        }
        version = version == null || version.isBlank() ? LATEST : version;
        Objects.requireNonNull(workflowId, "workflowId is required");

        workflowVersion = workflowVersion == null || workflowVersion.isBlank() ? LATEST : workflowVersion;
        promptProfile = promptProfile == null ? workflowId : promptProfile;
        policies = policies == null ? AgentPolicies.empty() : policies;
        modelConfig = modelConfig == null ? ModelConfig.defaults() : modelConfig;
        workspaceTemplate = workspaceTemplate == null ? "default" : workspaceTemplate;
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** @return 定义唯一键，形如 {@code research@1.0.0} */
    public String key() {
        return id + "@" + version;
    }

    /**
     * @param id      Agent 标识
     * @param version 版本号
     * @return 流式构建器
     */
    public static Builder builder(String id, String version) {
        return new Builder(id, version);
    }

    /**
     * @param id Agent 标识
     * @return 版本为 {@link #LATEST} 的流式构建器
     */
    public static Builder builder(String id) {
        return new Builder(id, LATEST);
    }

    /**
     * @param policies 新的策略集合
     * @return 覆盖策略后的定义
     */
    public AgentDefinition withPolicies(AgentPolicies policies) {
        return new AgentDefinition(id, version, workflowId, workflowVersion, promptProfile,
                policies, modelConfig, workspaceTemplate, metadata);
    }

    /**
     * @param modelConfig 新的模型配置
     * @return 覆盖模型配置后的定义
     */
    public AgentDefinition withModelConfig(ModelConfig modelConfig) {
        return new AgentDefinition(id, version, workflowId, workflowVersion, promptProfile,
                policies, modelConfig, workspaceTemplate, metadata);
    }

    /**
     * @param template 工作区模板名
     * @return 覆盖工作区模板后的定义
     */
    public AgentDefinition withWorkspaceTemplate(String template) {
        return new AgentDefinition(id, version, workflowId, workflowVersion, promptProfile,
                policies, modelConfig, template, metadata);
    }

    /** {@link AgentDefinition} 的流式构建器，覆盖常见定义形态。 */
    public static final class Builder {

        private final String id;
        private String version;
        private String workflowId;
        private String workflowVersion = LATEST;
        private String promptProfile;
        private AgentPolicies policies = AgentPolicies.empty();
        private ModelConfig modelConfig = ModelConfig.defaults();
        private String workspaceTemplate = "default";
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private Builder(String id, String version) {
            this.id = Objects.requireNonNull(id, "agent id is required");
            this.version = version;
        }

        /**
         * @param version 版本号
         * @return 当前构建器
         */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        /**
         * @param workflowId 绑定的 Workflow id（版本取 latest）
         * @return 当前构建器
         */
        public Builder workflow(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        /**
         * @param workflowId      绑定的 Workflow id
         * @param workflowVersion 绑定的 Workflow 版本
         * @return 当前构建器
         */
        public Builder workflow(String workflowId, String workflowVersion) {
            this.workflowId = workflowId;
            this.workflowVersion = workflowVersion;
            return this;
        }

        /**
         * @param promptProfile Prompt 配置档名
         * @return 当前构建器
         */
        public Builder promptProfile(String promptProfile) {
            this.promptProfile = promptProfile;
            return this;
        }

        /**
         * @param modelConfig 模型配置
         * @return 当前构建器
         */
        public Builder model(ModelConfig modelConfig) {
            this.modelConfig = modelConfig;
            return this;
        }

        /**
         * @param provider 模型提供方标识
         * @param model    模型名
         * @return 当前构建器
         */
        public Builder model(String provider, String model) {
            this.modelConfig = ModelConfig.of(provider, model);
            return this;
        }

        /**
         * @param toolPolicy 工具策略
         * @return 当前构建器
         */
        public Builder toolPolicy(ToolPolicy toolPolicy) {
            this.policies = policies.withTool(toolPolicy);
            return this;
        }

        /**
         * @param policies 完整策略集合
         * @return 当前构建器
         */
        public Builder policies(AgentPolicies policies) {
            this.policies = policies;
            return this;
        }

        /**
         * @param quota 配额策略
         * @return 当前构建器
         */
        public Builder quota(QuotaPolicy quota) {
            this.policies = policies.withQuota(quota);
            return this;
        }

        /**
         * 记录 Agent 级默认缓存策略，未声明缓存的节点会继承它。
         *
         * @param cachePolicy 缓存策略
         * @return 当前构建器
         */
        public Builder cache(CachePolicy cachePolicy) {
            this.metadata.put("cache", cachePolicy);
            return this;
        }

        /**
         * @param workspaceTemplate 工作区模板名
         * @return 当前构建器
         */
        public Builder workspaceTemplate(String workspaceTemplate) {
            this.workspaceTemplate = workspaceTemplate;
            return this;
        }

        /**
         * @param key   元数据键
         * @param value 元数据值
         * @return 当前构建器
         */
        public Builder metadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        /**
         * 构建 Agent 定义。
         *
         * @return 新的 {@link AgentDefinition}
         * @throws IllegalStateException 未指定 workflow 时抛出
         */
        public AgentDefinition build() {
            if (workflowId == null) {
                throw new IllegalStateException("workflow(...) is required for agent '" + id + "'");
            }
            return new AgentDefinition(id, version, workflowId, workflowVersion, promptProfile,
                    policies, modelConfig, workspaceTemplate, metadata);
        }
    }
}
