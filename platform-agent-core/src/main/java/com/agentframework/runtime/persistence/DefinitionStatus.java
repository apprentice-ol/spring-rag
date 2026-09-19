package com.agentframework.runtime.persistence;

/**
 * 定义状态：草稿 / 已发布 / 已归档。
 *
 * <p>引擎只读取 {@link #PUBLISHED}；草稿用于编辑与校验试错，归档保留历史但不再可加载。</p>
 */
public enum DefinitionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
