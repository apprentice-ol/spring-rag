package com.agentframework.definition.view;

import com.agentframework.definition.policy.PolicyBinding;
import com.agentframework.definition.region.RegionDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域视图：显式声明的治理锚点及其策略绑定。
 *
 * @param id           区域 id
 * @param paradigm     范式标签
 * @param nodeIds      区域包含的节点
 * @param guards       守卫绑定名
 * @param filters      过滤器绑定名
 * @param interceptors 拦截器绑定名
 * @param hasQuota     是否声明了区域配额
 * @param loop         循环视图，可为 null
 */
public record RegionView(
        String id,
        String paradigm,
        List<String> nodeIds,
        List<String> guards,
        List<String> filters,
        List<String> interceptors,
        boolean hasQuota,
        LoopView loop) {

    public RegionView {
        nodeIds = List.copyOf(nodeIds == null ? List.of() : nodeIds);
        guards = List.copyOf(guards == null ? List.of() : guards);
        filters = List.copyOf(filters == null ? List.of() : filters);
        interceptors = List.copyOf(interceptors == null ? List.of() : interceptors);
    }

    /**
     * @param region 区域定义
     * @return 视图
     */
    public static RegionView of(RegionDefinition region) {
        return new RegionView(region.id(), region.paradigm().name(), region.nodeIds(),
                names(region.policy().guards()), names(region.policy().filters()),
                names(region.policy().interceptors()), region.policy().hasQuota(),
                LoopView.of(region.loop()));
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("paradigm", paradigm);
        document.put("nodeIds", nodeIds);
        document.put("guards", guards);
        document.put("filters", filters);
        document.put("interceptors", interceptors);
        document.put("hasQuota", hasQuota);
        document.put("loop", loop == null ? null : loop.toDocument());
        return document;
    }

    /**
     * @param bindings 绑定列表
     * @return 名字列表
     */
    private static List<String> names(List<PolicyBinding> bindings) {
        return bindings.stream().map(PolicyBinding::name).toList();
    }
}
