package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.engine.core.BranchInvoker;
import com.agentframework.engine.core.EngineConfig;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Cursor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行节点执行器：扇出到多个分支节点并按策略汇聚。
 *
 * <p>分支通过运行时的 {@link BranchInvoker} 执行，因此守卫、过滤器、拦截器对分支同样生效；
 * 并发使用虚拟线程，避免阻塞平台线程。</p>
 */
public final class ParallelNodeExecutor implements NodeExecutor {

    private final EngineConfig config;

    /** @param config 引擎配置，可为 null */
    public ParallelNodeExecutor(EngineConfig config) {
        this.config = config == null ? EngineConfig.defaults() : config;
    }

    @Override
    public NodeType type() {
        return NodeType.PARALLEL;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        ParallelNodeDefinition parallel = (ParallelNodeDefinition) node;
        BranchInvoker invoker = context.branchInvoker();
        if (invoker == null) {
            return NodeResult.failed(node.id(), "并行节点缺少节点调用入口，无法执行分支");
        }
        List<NodeResult> results = new ArrayList<>();
        int concurrency = Math.max(1, Math.min(parallel.maxConcurrency(), parallel.branches().size()));
        try (ExecutorService executor = Executors.newFixedThreadPool(concurrency,
                Thread.ofVirtual().name("parallel-branch-", 0).factory())) {
            List<Future<NodeResult>> futures = new ArrayList<>();
            for (String branch : parallel.branches()) {
                NodeDefinition branchNode = context.workflow().node(branch).orElse(null);
                if (branchNode == null) {
                    results.add(NodeResult.failed(branch, "分支节点不存在：" + branch));
                    continue;
                }
                NodeContext branchContext = context.withCursor(Cursor.at(branchNode.id()));
                futures.add(executor.submit(() -> invoker.invokeNode(branchNode, branchContext)));
            }
            for (Future<NodeResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    results.add(NodeResult.failed(node.id(), "分支执行异常：" + e.getMessage()));
                }
            }
        }
        List<NodeResult> successful = results.stream().filter(result -> !result.isFailed()).toList();
        if (successful.isEmpty()) {
            return NodeResult.failed(node.id(), "并行的所有分支均失败");
        }
        if (parallel.joinStrategy() != ParallelNodeDefinition.JoinStrategy.ALL && !successful.isEmpty()) {
            NodeResult first = successful.get(0);
            return NodeResult.completed(node.id(), first.output(), joinWrites(parallel, results))
                    .withMetadata("joinStrategy", parallel.joinStrategy().name());
        }
        StringBuilder joined = new StringBuilder();
        results.forEach(result -> joined.append(result.nodeId()).append(": ").append(result.output()).append('\n'));
        return NodeResult.completed(node.id(), joined.toString().stripTrailing(), joinWrites(parallel, results))
                .withMetadata("joinStrategy", parallel.joinStrategy().name())
                .withMetadata("branches", results.size());
    }

    /**
     * 汇聚分支输出。
     *
     * @param parallel 并行节点定义
     * @param results  分支结果
     * @return 槽位写入
     */
    private Map<String, Object> joinWrites(ParallelNodeDefinition parallel, List<NodeResult> results) {
        Map<String, Object> perBranch = new LinkedHashMap<>();
        results.forEach(result -> perBranch.put(result.nodeId(), result.output()));
        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(parallel.outputSlot(), perBranch);
        return writes;
    }
}
