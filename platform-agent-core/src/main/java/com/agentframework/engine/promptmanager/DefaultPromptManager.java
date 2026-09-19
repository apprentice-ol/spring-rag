package com.agentframework.engine.promptmanager;

import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.engine.policy.PolicyGuards;
import com.agentframework.engine.policy.ResolvedPolicy;
import com.agentframework.runtime.event.EventBus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 默认 Prompt 管理器。
 *
 * <p>构建顺序固定：解析资产 → 渲染模板 → 过滤（{@code PROMPT} 阶段）→ 守卫
 * （{@code BEFORE_PROMPT}）。顺序固定意味着过滤与守卫的行为可预期、可测试。</p>
 */
public final class DefaultPromptManager implements PromptManager {

    private final PromptProvider provider;
    private final Map<String, PromptRenderer> renderers = new LinkedHashMap<>();
    private final MiddlewarePipeline middleware;
    private final PromptRenderer defaultRenderer;
    private final EventBus events;

    /**
     * @param provider   资产来源
     * @param renderers  可用渲染器，可为空
     * @param middleware 中间件管道，null 表示跳过过滤与守卫
     */
    public DefaultPromptManager(PromptProvider provider, List<PromptRenderer> renderers,
            MiddlewarePipeline middleware) {
        this(provider, renderers, middleware, null);
    }

    /**
     * @param provider   资产来源
     * @param renderers  可用渲染器，可为空
     * @param middleware 中间件管道，null 表示跳过过滤与守卫
     * @param events     事件总线，用于守卫失败开放审计，可为 null
     */
    public DefaultPromptManager(PromptProvider provider, List<PromptRenderer> renderers,
            MiddlewarePipeline middleware, EventBus events) {
        this.provider = provider;
        if (renderers != null) {
            renderers.forEach(renderer -> this.renderers.put(renderer.name(), renderer));
        }
        this.defaultRenderer = this.renderers.computeIfAbsent("template",
                ignored -> new TemplatePromptRenderer());
        this.middleware = middleware;
        this.events = events;
    }

    @Override
    public Prompt resolve(String promptId, String promptVersion) {
        return provider.get(promptId, promptVersion);
    }

    @Override
    public String render(Prompt prompt, PromptContext context) {
        PromptRenderer renderer = renderers.getOrDefault(prompt.renderer(), defaultRenderer);
        return renderer.render(prompt, context);
    }

    @Override
    public String build(PromptRequest request) {
        Prompt prompt = resolve(request.promptId(), request.promptVersion());
        String rendered = render(prompt, request.context());
        if (middleware == null) {
            return rendered;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(request.context().attributes());
        attributes.put("prompt.id", prompt.id());
        attributes.put("prompt.version", prompt.version());
        attributes.put("prompt.filterRefs", prompt.filterRefs());
        attributes.put("prompt.guardRefs", prompt.guardRefs());
        FilterContext filterContext = FilterContext.of(FilterPhase.PROMPT)
                .withOwner(request.context().sessionId(), request.context().nodeId())
                .withAttributes(attributes);
        ResolvedPolicy policy = request.policy();
        Object filtered = policy == null
                ? middleware.filter(FilterPhase.PROMPT, rendered, filterContext)
                : middleware.filter(FilterPhase.PROMPT, rendered, filterContext, policy.filters());
        String result = filtered == null ? "" : String.valueOf(filtered);
        GuardContext guardContext = GuardContext.of(GuardPhase.BEFORE_PROMPT, result)
                .withSession(request.context().sessionId(), null)
                .withOwner(null, request.context().nodeId())
                .withAttributes(attributes);
        Object allowed = policy == null
                ? middleware.requireGuard(GuardPhase.BEFORE_PROMPT, guardContext)
                : PolicyGuards.require(policy, GuardPhase.BEFORE_PROMPT, guardContext, events);
        return allowed == null ? result : String.valueOf(allowed);
    }

    /**
     * 注册渲染器，同名实现会被覆盖。
     *
     * @param renderer 渲染器
     * @return 当前管理器
     */
    public DefaultPromptManager register(PromptRenderer renderer) {
        if (renderer != null) {
            renderers.put(renderer.name(), renderer);
        }
        return this;
    }

    /** @return 已注册的渲染器名称 */
    public Set<String> rendererNames() {
        return Set.copyOf(renderers.keySet());
    }
}
