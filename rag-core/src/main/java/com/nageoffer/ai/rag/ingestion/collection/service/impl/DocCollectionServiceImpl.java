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
        log.info("[Collection] 归集: collectionId={}, docIds={}, 更新={}", collectionId, docIds.size(), updated);
        return updated;
    }
}
