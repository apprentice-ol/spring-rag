package com.nageoffer.ai.rag.ingestion.collection.controller;

import com.nageoffer.ai.rag.ingestion.collection.domain.entity.DocCollectionEntity;
import com.nageoffer.ai.rag.ingestion.collection.service.DocCollectionService;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 文档集合（文件集）CRUD + 归集。路径挂在 /docs 下，与 IngestionController 同源。 */
@RestController
@RequestMapping("/docs/collections")
@RequiredArgsConstructor
public class DocCollectionController {

    private final DocCollectionService collectionService;

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateCollectionRequest req) {
        Long id = collectionService.create(req.getName(), req.getDescription());
        return Map.of("id", id);
    }

    @GetMapping
    public List<DocCollectionEntity> list() {
        return collectionService.list();
    }

    /** 改集合名称/描述 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateCollectionRequest req) {
        collectionService.update(id, req.getName(), req.getDescription());
        return Map.of("id", id, "status", "UPDATED");
    }

    /** 删集合（逻辑删 + 解绑文档，不删文档） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        int unlinked = collectionService.delete(id);
        return Map.of("id", id, "status", "DELETED", "unlinked", unlinked);
    }

    /**
     * 批量归集/移动/移出。
     * <p>collectionId=null=移出集合（变独立文件）；非 null=移入/移动（已在他处的覆盖）。</p>
     */
    @PostMapping("/assign")
    public Map<String, Object> assign(@RequestBody AssignRequest req) {
        int updated = collectionService.assign(req.getCollectionId(), req.getDocIds());
        return Map.of("updated", updated);
    }

    @Data
    public static class CreateCollectionRequest {
        private String name;
        private String description;
    }

    @Data
    public static class UpdateCollectionRequest {
        private String name;
        private String description;
    }

    @Data
    public static class AssignRequest {
        private Long collectionId;
        private List<String> docIds;
    }
}
