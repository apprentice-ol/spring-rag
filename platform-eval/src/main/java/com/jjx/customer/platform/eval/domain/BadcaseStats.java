package com.jjx.customer.platform.eval.domain;

import java.util.Map;

/**
 * bad case 分布统计（管理页统计条）：针对性优化的依据——哪个领域问题最多、都是什么环节。
 *
 * @param total    总数（source=feedback 的条目，含已转正）
 * @param byStatus 按状态计数（DRAFT / READY / IGNORED）
 * @param byStage  按归因环节计数（TriageStage）
 * @param byDomain 按知识领域计数
 * @param byOrigin 按归集渠道计数
 */
public record BadcaseStats(
        long total,
        Map<String, Long> byStatus,
        Map<String, Long> byStage,
        Map<String, Long> byDomain,
        Map<String, Long> byOrigin
) {
}
