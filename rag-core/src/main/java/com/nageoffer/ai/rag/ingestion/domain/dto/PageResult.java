package com.nageoffer.ai.rag.ingestion.domain.dto;

import java.util.List;

/**
 * 通用分页返回。
 *
 * @param total   符合条件的总条数（前端分页器用）
 * @param records 当前页数据
 */
public record PageResult<T>(long total, List<T> records) {
}
