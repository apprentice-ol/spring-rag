package com.nageoffer.ai.rag.eval.controller;

import com.nageoffer.ai.rag.eval.dao.entity.EvalDatasetEntity;
import com.nageoffer.ai.rag.eval.dao.entity.EvalItemEntity;
import com.nageoffer.ai.rag.eval.domain.EvalItemRequest;
import com.nageoffer.ai.rag.eval.importer.LiveRagImportRequest;
import com.nageoffer.ai.rag.eval.importer.LiveRagImportResult;
import com.nageoffer.ai.rag.eval.importer.LiveRagImporter;
import com.nageoffer.ai.rag.eval.service.EvalService;
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

/** 评测数据集 / 条目 CRUD。 */
@RestController
@RequestMapping("/eval/datasets")
@RequiredArgsConstructor
public class EvalDatasetController {

    private final EvalService evalService;
    private final LiveRagImporter liveRagImporter;

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateDatasetRequest request) {
        Long id = evalService.createDataset(request.getName(), request.getDescription());
        return Map.of("id", id);
    }

    @GetMapping
    public List<EvalDatasetEntity> list() {
        return evalService.listDatasets();
    }

    /** 改数据集名称/描述 */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody UpdateDatasetRequest request) {
        evalService.updateDataset(id, request.getName(), request.getDescription());
        return Map.of("id", id, "status", "UPDATED");
    }

    /** 删数据集（级联删条目） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        evalService.deleteDataset(id);
        return Map.of("id", id, "status", "DELETED");
    }

    @PostMapping("/{id}/items")
    public Map<String, Object> addItems(@PathVariable Long id, @RequestBody List<EvalItemRequest> items) {
        evalService.addItems(id, items);
        return Map.of("added", items == null ? 0 : items.size());
    }

    @GetMapping("/{id}/items")
    public List<EvalItemEntity> listItems(@PathVariable Long id) {
        return evalService.listItems(id);
    }

    /** 删条目（原子扣减数据集 itemCount） */
    @DeleteMapping("/{id}/items/{itemId}")
    public Map<String, Object> deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        evalService.deleteItem(id, itemId);
        return Map.of("id", itemId, "status", "DELETED");
    }

    /** 启用/禁用条目 */
    @PutMapping("/{id}/items/{itemId}/enabled")
    public Map<String, Object> toggleItemEnabled(@PathVariable Long id, @PathVariable Long itemId,
                                                 @RequestBody ToggleEnabledRequest request) {
        int enabledValue = request.getEnabled() == null ? 1 : request.getEnabled();
        evalService.toggleItemEnabled(itemId, enabledValue);
        return Map.of("id", itemId, "enabled", enabledValue);
    }

    /**
     * 导入 LiveRAG 基准（同步执行：拉取 parquet → 抽样 → 支持文档入库 → 写评测条目）。
     * 50 题约 2-5 分钟，前端轮询 /eval/datasets 看 item_count 增长即可。
     */
    @PostMapping("/import/liverag")
    public LiveRagImportResult importLiveRag(@RequestBody(required = false) LiveRagImportRequest req) {
        return liveRagImporter.importSample(req == null ? new LiveRagImportRequest(null, null, null, null) : req);
    }

    @Data
    public static class CreateDatasetRequest {
        private String name;
        private String description;
    }

    @Data
    public static class UpdateDatasetRequest {
        private String name;
        private String description;
    }

    @Data
    public static class ToggleEnabledRequest {
        private Integer enabled;
    }
}
