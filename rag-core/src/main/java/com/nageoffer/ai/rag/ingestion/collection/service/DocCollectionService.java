package com.nageoffer.ai.rag.ingestion.collection.service;

import com.nageoffer.ai.rag.ingestion.collection.domain.entity.DocCollectionEntity;
import java.util.List;

/** 文档集合（文件集）服务：CRUD + 归集/移动/移出。 */
public interface DocCollectionService {

    /** 建集合，返回 id */
    Long create(String name, String description);

    /**
     * 按名查/建集合（存在即复用），返回 id。供导入器等程序化归集用——
     * 与 create 的区别：重名不抛异常而是复用；并发首建冲突按唯一约束兜底重查。
     */
    Long ensureCollection(String name, String description);

    /** 校验集合存在，返回集合名；不存在抛 ClientException（供归集前防脏引用） */
    String requireCollection(Long id);

    /** 列集合（含实时聚合的文档数 docCount） */
    List<DocCollectionEntity> list();

    /** 改集合名称/描述 */
    void update(Long id, String name, String description);

    /** 删集合（逻辑删 + 解绑文档：文档 collection_id 置 null，变独立文件，不删文档），返回解绑文档数 */
    int delete(Long id);

    /**
     * 批量归集/移动/移出。
     *
     * @param collectionId 目标集合 id；null=移出集合（变独立文件）
     * @param docIds       文档 docId 列表
     * @return 受影响文档数
     */
    int assign(Long collectionId, List<String> docIds);
}
