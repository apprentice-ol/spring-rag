package com.agentframework.runtime.persistence;

import com.agentframework.definition.ValidationReport;
import com.agentframework.definition.codec.DefinitionKind;
import java.time.Instant;
import java.util.Map;

/**
 * 定义记录：一条可入库的声明式定义及其治理元信息。
 *
 * @param kind         定义种类
 * @param id           定义 id
 * @param version      版本号
 * @param status       状态
 * @param document     规范文档（由 DefinitionLoader 产出）
 * @param report       入库时的校验报告
 * @param author       保存者
 * @param createdAt    创建时间
 * @param publishedAt  发布时间，未发布为 null
 */
public record DefinitionRecord(
        DefinitionKind kind,
        String id,
        String version,
        DefinitionStatus status,
        Map<String, Object> document,
        ValidationReport report,
        String author,
        Instant createdAt,
        Instant publishedAt) {

    public DefinitionRecord {
        if (kind == null || id == null || id.isBlank()) {
            throw new IllegalArgumentException("definition record requires kind and id");
        }
        version = version == null || version.isBlank() ? "latest" : version;
        status = status == null ? DefinitionStatus.DRAFT : status;
        document = document == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(document));
        report = report == null ? ValidationReport.empty() : report;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** @return 定义唯一键，形如 {@code workflow:ops@1.0.0} */
    public String key() {
        return kind.wireName() + ":" + id + "@" + version;
    }

    /**
     * @param status 新状态
     * @return 覆盖状态后的记录
     */
    public DefinitionRecord withStatus(DefinitionStatus status) {
        return new DefinitionRecord(kind, id, version, status, document, report, author, createdAt, publishedAt);
    }

    /**
     * @param author      保存者
     * @param publishedAt 发布时间
     * @return 覆盖治理信息后的记录
     */
    public DefinitionRecord withGovernance(String author, Instant publishedAt) {
        return new DefinitionRecord(kind, id, version, status, document, report, author, createdAt, publishedAt);
    }
}
