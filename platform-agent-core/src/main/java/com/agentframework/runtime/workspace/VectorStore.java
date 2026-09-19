package com.agentframework.runtime.workspace;

import java.util.List;
import java.util.Map;

/**
 * 工作区级向量索引，供检索类工具与记忆扩展使用。
 *
 * <p>它属于可替换的基础设施，而不是框架逻辑。</p>
 */
public interface VectorStore {

    /** @param record 写入或覆盖的记录 */
    void upsert(VectorRecord record);

    /**
     * @param vector 查询向量
     * @param topK   返回条数
     * @param filter 元数据过滤条件
     * @return 按相似度降序排列的命中结果
     */
    List<VectorMatch> query(float[] vector, int topK, Map<String, Object> filter);

    /**
     * @param vector 查询向量
     * @param topK   返回条数
     * @return 命中结果
     */
    default List<VectorMatch> query(float[] vector, int topK) {
        return query(vector, topK, Map.of());
    }

    /** @param id 需要删除的记录 id */
    void delete(String id);

    /** @return 索引中的记录数 */
    int size();
}
