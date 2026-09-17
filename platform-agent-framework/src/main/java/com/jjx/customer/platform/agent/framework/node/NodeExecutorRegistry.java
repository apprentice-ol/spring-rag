package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.node.NodeKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行器注册表：形态 → 执行器（重复注册即失败；缺失即报错，不静默降级）。
 */
public class NodeExecutorRegistry {

    private final Map<NodeKind, NodeExecutor> byKind = new LinkedHashMap<>();

    public NodeExecutorRegistry(List<NodeExecutor> executors) {
        if (executors != null) {
            for (NodeExecutor executor : executors) {
                NodeExecutor previous = byKind.putIfAbsent(executor.nodeKind(), executor);
                if (previous != null) {
                    throw new IllegalStateException("节点形态重复注册: " + executor.nodeKind());
                }
            }
        }
    }

    public NodeExecutor require(NodeKind kind) {
        NodeExecutor executor = byKind.get(kind);
        if (executor == null) {
            throw new IllegalStateException("节点形态无执行器: " + kind + "（已注册: " + byKind.keySet() + "）");
        }
        return executor;
    }
}
