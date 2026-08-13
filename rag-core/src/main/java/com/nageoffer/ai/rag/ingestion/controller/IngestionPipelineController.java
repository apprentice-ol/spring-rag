package com.nageoffer.ai.rag.ingestion.controller;

import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionPipelineEntity;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.PipelineDefinition;
import com.nageoffer.ai.rag.ingestion.service.IngestionPipelineService;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 流水线 CRUD + 查定义。 */
@RestController
@RequestMapping("/ingestion/pipelines")
@RequiredArgsConstructor
public class IngestionPipelineController {

    private final IngestionPipelineService pipelineService;

    @PostMapping
    public Map<String, Object> create(@RequestBody CreatePipelineRequest req) {
        Long id = pipelineService.createPipeline(req.getName(), req.getDescription(), req.getNodes());
        return Map.of("id", id);
    }

    @GetMapping
    public List<IngestionPipelineEntity> list() {
        return pipelineService.listPipelines();
    }

    @GetMapping("/{id}")
    public IngestionPipelineEntity get(@PathVariable Long id) {
        return pipelineService.getPipeline(id);
    }

    @GetMapping("/{id}/definition")
    public PipelineDefinition getDefinition(@PathVariable Long id) {
        return pipelineService.getDefinition(String.valueOf(id));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        pipelineService.deletePipeline(id);
        return Map.of("deleted", id);
    }

    @Data
    public static class CreatePipelineRequest {
        private String name;
        private String description;
        private List<NodeConfig> nodes;
    }
}
