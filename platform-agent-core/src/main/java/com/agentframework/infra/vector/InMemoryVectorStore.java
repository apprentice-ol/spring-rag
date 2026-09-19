package com.agentframework.infra.vector;

import com.agentframework.runtime.workspace.VectorMatch;
import com.agentframework.runtime.workspace.VectorRecord;
import com.agentframework.runtime.workspace.VectorStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存向量索引：余弦相似度检索 + 元数据过滤。
 *
 * <p>用于本地开发与测试；生产环境请替换为专用向量库实现。</p>
 */
public final class InMemoryVectorStore implements VectorStore {

    private final Map<String, VectorRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized void upsert(VectorRecord record) {
        if (record != null) {
            records.put(record.id(), record);
        }
    }

    @Override
    public synchronized List<VectorMatch> query(float[] vector, int topK, Map<String, Object> filter) {
        if (vector == null || vector.length == 0) {
            return List.of();
        }
        List<VectorMatch> matches = new ArrayList<>();
        for (VectorRecord record : records.values()) {
            if (!matchesFilter(record, filter)) {
                continue;
            }
            matches.add(new VectorMatch(record.id(), cosine(vector, record.vector()), record.text(),
                    record.metadata()));
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(VectorMatch::score).reversed())
                .limit(topK <= 0 ? matches.size() : topK)
                .toList();
    }

    @Override
    public synchronized void delete(String id) {
        records.remove(id);
    }

    @Override
    public synchronized int size() {
        return records.size();
    }

    /**
     * 元数据过滤：所有条件必须同时满足。
     *
     * @param record 记录
     * @param filter 过滤条件
     * @return 命中返回 true
     */
    private boolean matchesFilter(VectorRecord record, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        return filter.entrySet().stream().allMatch(entry -> {
            Object actual = record.metadata().get(entry.getKey());
            return actual != null && actual.equals(entry.getValue());
        });
    }

    /**
     * 计算余弦相似度。
     *
     * @param left  向量 A
     * @param right 向量 B
     * @return 相似度，维度不一致或存在零向量时返回 0
     */
    private double cosine(float[] left, float[] right) {
        if (right == null || left.length != right.length) {
            return 0d;
        }
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0d || rightNorm == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
