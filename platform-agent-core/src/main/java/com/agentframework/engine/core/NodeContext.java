package com.agentframework.engine.core;

import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.workspace.Workspace;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点执行上下文：执行器可读的全部运行态。
 *
 * @param agent         Agent 实例
 * @param session       当前会话
 * @param workflow      当前工作流
 * @param cursor        当前游标
 * @param slots         槽位容器
 * @param workspace     工作区，可为 null
 * @param input         本次运行的输入
 * @param trace         链路上下文
 * @param parentSpan    父 span，可为 null
 * @param branchInvoker 节点调用入口，由运行时注入，供并行 / 子工作流节点复用
 * @param attributes    附加属性
 */
public record NodeContext(
        Agent agent,
        Session session,
        WorkflowDefinition workflow,
        Cursor cursor,
        Slots slots,
        Workspace workspace,
        Input input,
        TraceContext trace,
        Span parentSpan,
        BranchInvoker branchInvoker,
        Map<String, Object> attributes) {

    public NodeContext {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param slots 新的槽位容器
     * @return 替换槽位后的上下文
     */
    public NodeContext withSlots(Slots slots) {
        return new NodeContext(agent, session, workflow, cursor, slots, workspace, input, trace, parentSpan,
                branchInvoker, attributes);
    }

    /**
     * @param workflow 新的工作流
     * @param slots    新的槽位容器
     * @return 替换工作流与槽位后的上下文
     */
    public NodeContext withWorkflow(WorkflowDefinition workflow, Slots slots) {
        return new NodeContext(agent, session, workflow, cursor, slots, workspace, input, trace, parentSpan,
                branchInvoker, attributes);
    }

    /**
     * @param input 新的输入
     * @return 替换输入后的上下文
     */
    public NodeContext withInput(Input input) {
        return new NodeContext(agent, session, workflow, cursor, slots, workspace, input, trace, parentSpan,
                branchInvoker, attributes);
    }

    /**
     * @param cursor 新的游标
     * @return 替换游标后的上下文
     */
    public NodeContext withCursor(Cursor cursor) {
        return new NodeContext(agent, session, workflow, cursor, slots, workspace, input, trace, parentSpan,
                branchInvoker, attributes);
    }

    /**
     * @param parentSpan 父 span
     * @return 替换父 span 后的上下文
     */
    public NodeContext withParentSpan(Span parentSpan) {
        return new NodeContext(agent, session, workflow, cursor, slots, workspace, input, trace, parentSpan,
                branchInvoker, attributes);
    }

    /**
     * @param key   属性名
     * @param value 属性值
     * @return 追加属性后的上下文
     */
    public NodeContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new NodeContext(agent, session, workflow, cursor, slots, workspace, input, trace, parentSpan,
                branchInvoker, merged);
    }

    /** @return 变量表：槽位值 + 输入数据，供表达式求值使用 */
    public Map<String, Object> variables() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("slots", regionScopedSlots());
        variables.put("slot", regionScopedSlots());
        variables.put("input", input == null ? Map.of() : input.payload());
        variables.put("sessionId", session == null ? null : session.id());
        variables.put("nodeId", cursor == null ? null : cursor.nodeId());
        return variables;
    }

    /**
     * 槽位视图：物理槽位全量 + 当前 Region 局部槽的短名别名。
     *
     * <p>当前节点位于带 {@code slotPrefix} 的 Region 内时，{@code {prefix}_{name}} 物理槽
     * 会同时以短名 {@code name} 暴露（不覆盖同名的全局槽），供表达式与 Prompt 模板
     * 用短名引用，执行器无需拼接前缀。写入侧由运行时在 slotWrites 写回时展开。</p>
     *
     * @return 槽位视图（含短名别名）
     */
    private Map<String, Object> regionScopedSlots() {
        Map<String, Object> values = slots == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slots.asMap());
        if (workflow == null || cursor == null || cursor.nodeId() == null) {
            return values;
        }
        workflow.regionContaining(cursor.nodeId())
                .filter(RegionDefinition::hasSlotPrefix)
                .ifPresent(region -> {
                    String prefix = region.slotPrefix() + "_";
                    Map<String, Object> aliases = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : values.entrySet()) {
                        String name = entry.getKey();
                        if (name.startsWith(prefix)) {
                            aliases.putIfAbsent(name.substring(prefix.length()), entry.getValue());
                        }
                    }
                    values.putAll(aliases);
                });
        return values;
    }
}
