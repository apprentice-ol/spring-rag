package com.jjx.customer.platform.ingestion.catalog;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jjx.customer.platform.document.DocumentCatalog;
import com.jjx.customer.platform.ingestion.domain.entity.DocumentEntity;
import com.jjx.customer.platform.ingestion.mapper.DocumentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 文档目录实现：读 sa_document 的文档名（入库域持有文档表，编排层只取契约视图）。 */
@Component
@RequiredArgsConstructor
public class IngestionDocumentCatalog implements DocumentCatalog {

    private final DocumentMapper documentMapper;

    @Override
    public List<String> allDocumentNames() {
        return documentMapper.selectList(Wrappers.lambdaQuery(DocumentEntity.class)
                        .select(DocumentEntity::getName))
                .stream()
                .map(DocumentEntity::getName)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Map<String, String> sourceLocations(List<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectList(Wrappers.lambdaQuery(DocumentEntity.class)
                        .in(DocumentEntity::getDocId, docIds)
                        .select(DocumentEntity::getDocId, DocumentEntity::getSourceLocation))
                .stream()
                .filter(d -> d.getSourceLocation() != null)
                .collect(Collectors.toMap(DocumentEntity::getDocId, DocumentEntity::getSourceLocation));
    }
}
