package com.agentframework.engine.view;

import com.agentframework.runtime.persistence.CheckpointEntry;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检查点摘要：供前端画"执行轨迹条"。
 *
 * @param step   步号
 * @param nodeId 该步所在节点
 * @param edge   走到该步经过的边，可为 null
 * @param at     记录时间
 */
public record CheckpointSummary(int step, String nodeId, String edge, String at) {

    /**
     * @param entry 检查点
     * @return 摘要
     */
    public static CheckpointSummary of(CheckpointEntry entry) {
        return new CheckpointSummary(entry.step(), entry.session().cursor().nodeId(), entry.edge(),
                entry.at().toString());
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("step", step);
        document.put("nodeId", nodeId);
        document.put("edge", edge);
        document.put("at", at);
        return document;
    }
}
