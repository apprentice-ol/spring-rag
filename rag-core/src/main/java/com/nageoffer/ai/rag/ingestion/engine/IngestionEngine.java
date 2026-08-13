package com.nageoffer.ai.rag.ingestion.engine;

import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 流水线执行引擎 - 基于节点连线的链式执行（搬自原 ragent，改 package + StrUtil→StringUtils）。
 *
 * <p>execute: 构建 nodeConfigMap → validatePipeline(环检测) → findStartNode → executeChain(while nextNodeId)。
 * 每个节点的执行结果（成功/跳过/失败/耗时/output）记入 context.logs。
 */
@Slf4j
@Component
public class IngestionEngine {

    private final Map<String, IngestionNode> nodeMap;
    private final ConditionEvaluator conditionEvaluator;
    private final NodeOutputExtractor outputExtractor;

    public IngestionEngine(
            List<IngestionNode> nodes,
            ConditionEvaluator conditionEvaluator,
            NodeOutputExtractor outputExtractor) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(IngestionNode::getNodeType, n -> n));
        this.conditionEvaluator = conditionEvaluator;
        this.outputExtractor = outputExtractor;
    }


    /**
     * 执行流水线
     * @param pipeline 流水线定义
     * @param context 流水线执行上下文
     * @return  流水线执行上下文
     */
    public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
        if (context.getLogs() == null) {
            context.setLogs(new ArrayList<>());
        }
        if (context.getMetadata() == null) {
            context.setMetadata(new HashMap<>());
        }
        context.setStatus(IngestionStatus.RUNNING);

        Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());
        this.validatePipeline(nodeConfigMap);

        String startNodeId = this.findStartNode(nodeConfigMap);
        if (!StringUtils.hasText(startNodeId)) {
            log.error("流水线未找到起始节点");
            throw new ClientException("流水线未找到起始节点");
        }
        log.info("流水线从节点开始执行: {}", startNodeId);

        this.executeChain(startNodeId, nodeConfigMap, context);

        if (context.getStatus() == IngestionStatus.RUNNING) {
            context.setStatus(IngestionStatus.COMPLETED);
        }
        return context;
    }

    /**
     * 构建节点配置映射
     * @param nodes 节点列表
     * @return 节点配置映射
     */
    private Map<String, NodeConfig> buildNodeConfigMap(List<NodeConfig> nodes) {
        if (nodes == null) {
            return Collections.emptyMap();
        }
        return nodes.stream().collect(Collectors.toMap(NodeConfig::getNodeId, n -> n));
    }

    /**
     * 环检测 + nextNodeId 连续性校验。
     * @param nodeConfigMap 节点配置映射
     */
    private void validatePipeline(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeConfigMap.keySet()) {
            if (visited.contains(nodeId)) {
                continue;
            }
            Set<String> path = new HashSet<>();
            String current = nodeId;
            while (current != null) {
                if (path.contains(current)) {
                    throw new ClientException("流水线存在环: " + current);
                }
                path.add(current);
                visited.add(current);
                NodeConfig config = nodeConfigMap.get(current);
                if (config == null) {
                    break;
                }
                String nextId = config.getNextNodeId();
                if (StringUtils.hasText(nextId)) {
                    if (!nodeConfigMap.containsKey(nextId)) {
                        throw new ClientException("找不到下一个节点: " + nextId + "，被节点 " + current + " 引用");
                    }
                    current = nextId;
                } else {
                    break;
                }
            }
        }
    }

    /** 找起始节点（没有被任何节点引用的节点）。 */
    private String findStartNode(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> referencedNodes = nodeConfigMap.values().stream()
                .map(NodeConfig::getNextNodeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return nodeConfigMap.keySet().stream()
                .filter(nodeId -> !referencedNodes.contains(nodeId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 执行节点链
     * @param nodeId 起始节点ID
     * @param nodeConfigMap 节点配置映射
     * @param context 流水线执行上下文
     */
    private void executeChain(String nodeId, Map<String, NodeConfig> nodeConfigMap, IngestionContext context) {
        String currentNodeId = nodeId;
        int executedCount = 0;
        final int maxNodes = nodeConfigMap.size();
        while (currentNodeId != null) {
            if (executedCount++ > maxNodes) {
                throw new ClientException("执行节点数超过上限，可能存在死循环");
            }
            NodeConfig config = nodeConfigMap.get(currentNodeId);
            if (config == null) {
                log.warn("未找到节点配置: {}", currentNodeId);
                break;
            }
            log.info("开始执行节点: {}", currentNodeId);
            NodeResult result = executeNode(context, config);
            if (!result.isSuccess()) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(result.getError());
                log.error("节点 {} 执行失败: {}", currentNodeId, result.getMessage());
                break;
            }
            if (!result.isShouldContinue()) {
                log.info("流水线在节点 {} 停止", currentNodeId);
                break;
            }
            currentNodeId = config.getNextNodeId();
        }
        log.info("流水线执行完成，共执行 {} 个节点", executedCount);
    }

    /**
     * 执行节点
     * @param context 流水线执行上下文
     * @param nodeConfig 节点配置
     * @return 节点执行结果
     */
    private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
        String nodeType = nodeConfig.getNodeType();
        String nodeId = nodeConfig.getNodeId();

        IngestionNode node = nodeMap.get(nodeType);
        if (node == null) {
            return NodeResult.fail(new IllegalStateException("未找到节点类型: " + nodeType));
        }

        if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
            if (!conditionEvaluator.evaluate(context, nodeConfig.getCondition())) {
                NodeResult skip = NodeResult.skip("条件未满足");
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message(skip.getMessage())
                        .durationMs(0)
                        .success(true)
                        .output(outputExtractor.extract(context, nodeConfig))
                        .build());
                return skip;
            }
        }

        long start = System.currentTimeMillis();
        try {
            NodeResult result = node.execute(context, nodeConfig);
            long duration = System.currentTimeMillis() - start;
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(result.getMessage())
                    .durationMs(duration)
                    .success(result.isSuccess())
                    .error(result.getError() == null ? null : result.getError().getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());
            log.info("节点 {} 执行完成，耗时 {}ms: {}", nodeId, duration, result.getMessage());
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .message(e.getMessage())
                    .durationMs(duration)
                    .success(false)
                    .error(e.getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());
            log.error("节点 {} 执行失败，耗时 {}ms", nodeId, duration, e);
            return NodeResult.fail(e);
        }
    }
}
