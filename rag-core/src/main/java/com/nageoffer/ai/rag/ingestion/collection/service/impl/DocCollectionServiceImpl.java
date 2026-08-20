package com.nageoffer.ai.rag.ingestion.collection.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.ingestion.collection.domain.entity.DocCollectionEntity;
import com.nageoffer.ai.rag.ingestion.collection.mapper.DocCollectionMapper;
import com.nageoffer.ai.rag.ingestion.collection.service.DocCollectionService;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 文档集合服务实现。
 *
 * <p>docCount 不用冗余列，list 时用 {@code SELECT collection_id, COUNT(*) FROM sa_document GROUP BY
 * collection_id} 聚合回填，避免并发计数漂移（参照评测模块 addItems 的非原子 read-modify-write 反例）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocCollectionServiceImpl implements DocCollectionService {

    private final DocCollectionMapper collectionMapper;
    private final DocumentMapper documentMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 向量表名（与 spring.ai.vectorstore.pgvector.table-name 同源，避免硬编码漂移） */
    @Value("${spring.ai.vectorstore.pgvector.table-name:spring_ai_store_vector}")
    private String vectorTable;

    @Override
    @Transactional
    public Long create(String name, String description) {
        if (!StringUtils.hasText(name)) {
            throw new ClientException("集合名称不能为空");
        }
        DocCollectionEntity c = new DocCollectionEntity();
        c.setName(name.trim());
        c.setDescription(description);
        try {
            collectionMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw new ClientException("集合名称已存在: " + name);
        }
        return c.getId();
    }

    @Override
    @Transactional
    public Long ensureCollection(String name, String description) {
        if (!StringUtils.hasText(name)) {
            throw new ClientException("集合名称不能为空");
        }
        String trimmed = name.trim();
        DocCollectionEntity existing = collectionMapper.selectOne(new LambdaQueryWrapper<DocCollectionEntity>()
                .eq(DocCollectionEntity::getName, trimmed));
        if (existing != null) {
            return existing.getId();
        }
        DocCollectionEntity c = new DocCollectionEntity();
        c.setName(trimmed);
        c.setDescription(description);
        try {
            collectionMapper.insert(c);
            return c.getId();
        } catch (DuplicateKeyException e) {
            // 并发首建同名集合：另一请求已建，重查复用
            return collectionMapper.selectOne(new LambdaQueryWrapper<DocCollectionEntity>()
                            .eq(DocCollectionEntity::getName, trimmed))
                    .getId();
        }
    }

    @Override
    public String requireCollection(Long id) {
        if (id == null) {
            throw new ClientException("集合 ID 不能为空");
        }
        DocCollectionEntity c = collectionMapper.selectById(id);
        if (c == null) {
            throw new ClientException("目标集合不存在: " + id);
        }
        return c.getName();
    }

    @Override
    public List<DocCollectionEntity> list() {
        List<DocCollectionEntity> list = collectionMapper.selectList(
                new LambdaQueryWrapper<DocCollectionEntity>().orderByDesc(DocCollectionEntity::getId));
        if (!list.isEmpty()) {
            Map<Long, Integer> counts = countByCollection();
            for (DocCollectionEntity c : list) {
                c.setDocCount(counts.getOrDefault(c.getId(), 0));
            }
        }
        return list;
    }

    /** 聚合各集合文档数：collection_id → count */
    private Map<Long, Integer> countByCollection() {
        Map<Long, Integer> counts = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT collection_id, COUNT(*) AS c FROM sa_document GROUP BY collection_id");
        for (Map<String, Object> row : rows) {
            Object cid = row.get("collection_id");
            if (cid != null) {
                counts.put(((Number) cid).longValue(), ((Number) row.get("c")).intValue());
            }
        }
        return counts;
    }

    @Override
    @Transactional
    public void update(Long id, String name, String description) {
        DocCollectionEntity c = collectionMapper.selectById(id);
        if (c == null) {
            throw new ClientException("集合不存在: " + id);
        }
        if (StringUtils.hasText(name)) {
            c.setName(name.trim());
        }
        if (description != null) {
            c.setDescription(description);
        }
        try {
            collectionMapper.updateById(c);
        } catch (DuplicateKeyException e) {
            throw new ClientException("集合名称已存在: " + name);
        }
    }

    @Override
    @Transactional
    public int delete(Long id) {
        DocCollectionEntity c = collectionMapper.selectById(id);
        if (c == null) {
            throw new ClientException("集合不存在: " + id);
        }
        // 解绑文档：collection_id 置 null，文档变独立文件（不删文档、不动向量）
        int unlinked = documentMapper.update(null, new LambdaUpdateWrapper<DocumentEntity>()
                .eq(DocumentEntity::getCollectionId, id)
                .set(DocumentEntity::getCollectionId, null));
        collectionMapper.deleteById(id);
        log.info("[Collection] 删除集合: id={}, name={}, 解绑文档={}", id, c.getName(), unlinked);
        return unlinked;
    }

    @Override
    @Transactional
    public int assign(Long collectionId, List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            throw new ClientException("docIds 不能为空");
        }
        if (collectionId != null && collectionMapper.selectById(collectionId) == null) {
            throw new ClientException("目标集合不存在: " + collectionId);
        }
        int updated = documentMapper.update(null, new LambdaUpdateWrapper<DocumentEntity>()
                .in(DocumentEntity::getDocId, docIds)
                .set(DocumentEntity::getCollectionId, collectionId));
        syncVectorCollectionMetadata(collectionId, docIds);
        log.info("[Collection] 归集: collectionId={}, docIds={}, 更新={}", collectionId, docIds.size(), updated);
        return updated;
    }

    /**
     * 归集时同步向量表 metadata 的 collection_id。
     * <p>检索过滤读的是向量 metadata（{@code metadata->>'collection_id'}），而归集此前只改 sa_document——
     * 两边错位后，按集合隔离的检索（评测 resolveCollection / 查询 collectionId 过滤）会限定到一个空集，
     * 全部 0 召回（服务器实际踩过：LiveRAG 导入时无集合，归集 1 后向量 metadata 仍为空）。
     * collectionId=null（移出集合）时从 metadata 删除该键。</p>
     */
    private void syncVectorCollectionMetadata(Long collectionId, List<String> docIds) {
        try {
            String placeholders = String.join(",", java.util.Collections.nCopies(docIds.size(), "?"));
            List<Object> params = new java.util.ArrayList<>(docIds);
            int synced;
            if (collectionId != null) {
                String sql = "UPDATE " + vectorTable + " SET metadata = jsonb_set(metadata, '{collection_id}', to_jsonb(?::text)) "
                        + "WHERE metadata->>'doc_id' IN (" + placeholders + ")";
                params.add(String.valueOf(collectionId));
                synced = jdbcTemplate.update(sql, params.toArray());
            } else {
                String sql = "UPDATE " + vectorTable + " SET metadata = metadata - 'collection_id' "
                        + "WHERE metadata->>'doc_id' IN (" + placeholders + ")";
                synced = jdbcTemplate.update(sql, params.toArray());
            }
            log.info("[Collection] 向量 metadata 同步: collectionId={}, 同步向量 {} 条", collectionId, synced);
        } catch (Exception e) {
            // 同步失败不阻断归集（sa_document 已更新），但必须留下可排查的完整告警
            log.error("[Collection] 向量 metadata 同步失败（sa_document 与向量表 collection_id 已错位，"
                    + "检索按集合过滤将查不到这些文档，请重试归集或手工修数据）: collectionId={}, docIds={}",
                    collectionId, docIds, e);
        }
    }
}
