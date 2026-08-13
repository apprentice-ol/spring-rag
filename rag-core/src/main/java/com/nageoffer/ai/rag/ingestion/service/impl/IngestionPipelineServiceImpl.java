package com.nageoffer.ai.rag.ingestion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionPipelineEntity;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionPipelineNodeEntity;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionPipelineNodeMapper;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.PipelineDefinition;

import java.util.List;

import com.nageoffer.ai.rag.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流水线服务（P2：DB 驱动，替内存硬编码）。
 *
 * <p>搬原 ragent IngestionPipelineServiceImpl 的 getDefinition 逻辑（读 DB → NodeConfig，
 * settings/condition 用 ObjectMapper.readTree 转 JsonNode），删 LogRecord/UserContext。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        IngestionPipelineEntity pipeline = resolvePipeline(pipelineId);
        if (pipeline == null) {
            throw new ClientException("流水线不存在: " + pipelineId);
        }
        List<IngestionPipelineNodeEntity> nodeDOs = nodeMapper.selectList(
                new LambdaQueryWrapper<IngestionPipelineNodeEntity>()
                        .eq(IngestionPipelineNodeEntity::getPipelineId, pipeline.getId())
                        .orderByAsc(IngestionPipelineNodeEntity::getId));
        List<NodeConfig> nodes = nodeDOs.stream().map(this::toNodeConfig).toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .build();
    }

    @Override
    @Transactional
    public Long createPipeline(String name, String description, List<NodeConfig> nodes) {
        IngestionPipelineEntity pipeline = new IngestionPipelineEntity();
        pipeline.setName(name);
        pipeline.setDescription(description);
        pipelineMapper.insert(pipeline);
        for (NodeConfig nc : nodes) {
            IngestionPipelineNodeEntity node = new IngestionPipelineNodeEntity();
            node.setPipelineId(pipeline.getId());
            node.setNodeId(nc.getNodeId());
            node.setNodeType(nc.getNodeType());
            node.setNextNodeId(nc.getNextNodeId());
            node.setSettingsJson(jsonToString(nc.getSettings()));
            node.setConditionJson(jsonToString(nc.getCondition()));
            nodeMapper.insert(node);
        }
        log.info("[Pipeline] 创建流水线: id={}, name={}, nodes={}", pipeline.getId(), name, nodes.size());
        return pipeline.getId();
    }

    @Override
    public IngestionPipelineEntity getPipeline(Long id) {
        return pipelineMapper.selectById(id);
    }

    @Override
    public List<IngestionPipelineEntity> listPipelines() {
        return pipelineMapper.selectList(new LambdaQueryWrapper<IngestionPipelineEntity>()
                .orderByDesc(IngestionPipelineEntity::getId));
    }

    @Override
    public void deletePipeline(Long id) {
        // 物理删除：pipeline name 全局唯一，逻辑删除会残留 name 占位，导致无法重建同名
        nodeMapper.deletePhysicalByPipelineId(id);
        pipelineMapper.deletePhysical(id);
    }

    /** pipelineId 可能是数字 id 或 name，先按 id 再按 name。 */
    private IngestionPipelineEntity resolvePipeline(String pipelineId) {
        try {
            Long id = Long.valueOf(pipelineId);
            IngestionPipelineEntity p = pipelineMapper.selectById(id);
            if (p != null) {
                return p;
            }
        } catch (NumberFormatException ignored) {
            // 不是数字，按 name 查
        }
        return pipelineMapper.selectOne(new LambdaQueryWrapper<IngestionPipelineEntity>()
                .eq(IngestionPipelineEntity::getName, pipelineId));
    }

    private NodeConfig toNodeConfig(IngestionPipelineNodeEntity node) {
        try {
            return NodeConfig.builder()
                    .nodeId(node.getNodeId())
                    .nodeType(node.getNodeType())
                    .settings(parseJson(node.getSettingsJson()))
                    .condition(parseJson(node.getConditionJson()))
                    .nextNodeId(node.getNextNodeId())
                    .build();
        } catch (Exception e) {
            throw new ClientException("节点配置解析失败: " + node.getNodeId(), e);
        }
    }

    private JsonNode parseJson(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return null;
        }
        return objectMapper.readTree(json);
    }

    private String jsonToString(JsonNode node) {
        return node == null ? null : node.toString();
    }
}
