package com.agentframework.crosscutting.guard;

import com.agentframework.definition.policy.ToolPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 内置守卫集合。
 *
 * <p>默认实现保持最小可用：{@link AllowAll} 不做任何限制，其余守卫按需挂载。
 * 自定义守卫实现 {@link Guard} 后通过扩展注册表接入。</p>
 */
public final class Guards {

    private Guards() {
    }

    /** 默认守卫：一律放行。 */
    public static final class AllowAll implements Guard {

        @Override
        public String name() {
            return "allow-all";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            return GuardDecision.allow();
        }
    }

    /** 内容守卫：按正则黑名单拦截敏感内容。 */
    public static final class Content implements Guard {

        private final String name;
        private final List<Pattern> denyPatterns;

        /**
         * @param name         守卫名称
         * @param denyPatterns 命即拒绝的正则表达式
         */
        public Content(String name, List<String> denyPatterns) {
            this.name = name == null ? "content-guard" : name;
            this.denyPatterns = (denyPatterns == null ? List.<String>of() : denyPatterns).stream()
                    .map(Pattern::compile)
                    .toList();
        }

        /**
         * @param patterns 命即拒绝的正则表达式
         * @return 内容守卫实例
         */
        public static Content of(String... patterns) {
            return new Content("content-guard", List.of(patterns));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            String text = context.payloadAsText();
            for (Pattern pattern : denyPatterns) {
                if (pattern.matcher(text).find()) {
                    return GuardDecision.deny("命中内容黑名单：" + pattern.pattern());
                }
            }
            return GuardDecision.allow();
        }
    }

    /** 基于角色的访问控制守卫：要求上下文属性中的角色与允许角色有交集。 */
    public static final class Rbac implements Guard {

        private final String attributeKey;
        private final List<String> allowedRoles;

        /**
         * @param attributeKey 存放角色的上下文属性名
         * @param allowedRoles 允许的角色列表
         */
        public Rbac(String attributeKey, List<String> allowedRoles) {
            this.attributeKey = attributeKey == null ? "roles" : attributeKey;
            this.allowedRoles = List.copyOf(allowedRoles == null ? List.of() : allowedRoles);
        }

        /**
         * @param roles 允许的角色
         * @return 使用默认属性名 {@code roles} 的 RBAC 守卫
         */
        public static Rbac of(String... roles) {
            return new Rbac("roles", List.of(roles));
        }

        @Override
        public String name() {
            return "rbac";
        }

        @Override
        public int order() {
            return 20;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            if (allowedRoles.isEmpty()) {
                return GuardDecision.allow();
            }
            Object raw = context.attributes().get(attributeKey);
            Collection<?> roles = switch (raw) {
                case null -> List.of();
                case Collection<?> collection -> collection;
                case String text -> List.of(text.split(","));
                default -> List.of(String.valueOf(raw));
            };
            boolean matched = roles.stream()
                    .map(role -> String.valueOf(role).trim())
                    .anyMatch(allowedRoles::contains);
            return matched
                    ? GuardDecision.allow()
                    : GuardDecision.deny("角色不满足要求，需要其中之一：" + allowedRoles);
        }
    }

    /**
     * 工具权限守卫：按 {@link ToolPolicy} 校验工具调用是否被允许。
     *
     * <p>载荷需实现 {@link ToolAware} 才能被识别；其它载荷直接放行。</p>
     */
    public static final class ToolPermission implements Guard {

        private final ToolPolicy policy;

        /** @param policy 工具策略 */
        public ToolPermission(ToolPolicy policy) {
            this.policy = policy == null ? ToolPolicy.allowAll() : policy;
        }

        @Override
        public String name() {
            return "tool-permission";
        }

        @Override
        public int order() {
            return 5;
        }

        @Override
        public boolean supports(GuardContext context) {
            return context.payload() instanceof ToolAware;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            if (!(context.payload() instanceof ToolAware toolCall)) {
                return GuardDecision.allow();
            }
            String toolId = toolCall.toolId();
            if (!policy.allows(toolId)) {
                return GuardDecision.deny("工具策略不允许调用 '" + toolId + "'");
            }
            if (policy.requiresApproval(toolId)) {
                return GuardDecision.askApproval("工具 '" + toolId + "' 需要人工审批");
            }
            return GuardDecision.allow();
        }
    }

    /** 输出合规守卫：限制长度，并可要求必须匹配指定正则。 */
    public static final class OutputCompliance implements Guard {

        private final int maxLength;
        private final Pattern requiredPattern;

        /**
         * @param maxLength       允许的最大字符数，≤0 表示不限制
         * @param requiredPattern 必须匹配的正则，null 表示不限制
         */
        public OutputCompliance(int maxLength, String requiredPattern) {
            this.maxLength = maxLength;
            this.requiredPattern = requiredPattern == null ? null : Pattern.compile(requiredPattern);
        }

        /**
         * @param maxLength 最大字符数
         * @return 仅限制长度的合规守卫
         */
        public static OutputCompliance maxLength(int maxLength) {
            return new OutputCompliance(maxLength, null);
        }

        @Override
        public String name() {
            return "output-compliance";
        }

        @Override
        public int order() {
            return 30;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            String text = Objects.requireNonNullElse(context.payloadAsText(), "");
            if (maxLength > 0 && text.length() > maxLength) {
                return GuardDecision.deny("输出长度 " + text.length() + " 超过上限 " + maxLength);
            }
            if (requiredPattern != null && !requiredPattern.matcher(text).find()) {
                return GuardDecision.deny("输出未匹配必需格式：" + requiredPattern.pattern());
            }
            return GuardDecision.allow();
        }
    }

    /**
     * 迭代上限守卫：读取当前节点的循环计数，达到上限时中断循环。
     *
     * <p>计数键由运行时按节点维护，因此同一个守卫实例可以挂在多个循环节点上。</p>
     */
    public static final class MaxIterations implements Guard {

        private final String name;
        private final int maxIterations;

        /**
         * @param name          守卫名称
         * @param maxIterations 允许的最大迭代次数，≤0 表示不限制
         */
        public MaxIterations(String name, int maxIterations) {
            this.name = name == null || name.isBlank() ? "max-iterations" : name;
            this.maxIterations = maxIterations;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int order() {
            return 1;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            if (maxIterations <= 0) {
                return GuardDecision.allow();
            }
            String counterKey = context.loopKey();
            int used = context.loopCounter(counterKey);
            if (used >= maxIterations) {
                return GuardDecision.breakLoop("max_iterations_reached:" + counterKey + "=" + used);
            }
            return GuardDecision.allow();
        }
    }

    /**
     * @param name          守卫名称
     * @param maxIterations 最大迭代次数
     * @return 迭代上限守卫
     */
    public static MaxIterations maxIterations(String name, int maxIterations) {
        return new MaxIterations(name, maxIterations);
    }

    /**
     * 成本预算守卫：Region 已消耗 token 超过上限时中断循环。
     *
     * <p>只对声明了 Region 的节点有效；用量来自运行时按 {@code (sessionId, regionId)} 的计量。</p>
     */
    public static final class CostBudget implements Guard {

        private final String name;
        private final long maxTokens;

        /**
         * @param name      守卫名称
         * @param maxTokens token 上限，≤0 表示不限制
         */
        public CostBudget(String name, long maxTokens) {
            this.name = name == null || name.isBlank() ? "cost-budget" : name;
            this.maxTokens = maxTokens;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int order() {
            return 2;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            if (maxTokens <= 0) {
                return GuardDecision.allow();
            }
            long used = context.regionTokens();
            if (used > maxTokens) {
                return GuardDecision.breakLoop("cost_budget_exceeded:" + used + "/" + maxTokens);
            }
            return GuardDecision.allow();
        }
    }

    /**
     * @param name      守卫名称
     * @param maxTokens token 上限
     * @return 成本预算守卫
     */
    public static CostBudget costBudget(String name, long maxTokens) {
        return new CostBudget(name, maxTokens);
    }
}
