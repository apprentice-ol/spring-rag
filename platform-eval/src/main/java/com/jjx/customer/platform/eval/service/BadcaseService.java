package com.jjx.customer.platform.eval.service;

import com.jjx.customer.platform.common.dto.PageResult;
import com.jjx.customer.platform.eval.domain.BadcaseCollectRequest;
import com.jjx.customer.platform.eval.domain.BadcaseManualRequest;
import com.jjx.customer.platform.eval.domain.BadcasePromoteRequest;
import com.jjx.customer.platform.eval.domain.BadcaseStats;
import com.jjx.customer.platform.eval.domain.BadcaseView;
import com.jjx.customer.platform.eval.domain.CandidateDoc;
import java.util.List;
import java.util.Map;

/**
 * bad case 归集服务：归集（统一模型：badcase 即 sa_eval_item 的一种）、归因、转正。
 *
 * <p>设计见 docs/plans/2026-10-11-badcase-collection.md。两区制：新 badcase 落
 * 「badcase 归集池」（DRAFT，不参与跑批），补完 ground truth 后转正到领域数据集（READY）。</p>
 */
public interface BadcaseService {

    /** 归集一条 bad case（幂等 by messageId：重复归集=更新原因/备注）。 */
    BadcaseView collect(BadcaseCollectRequest request);

    /** 手工录入一条 bad case（管理页；无线上现场，证据为空、归因 UNKNOWN）。 */
    BadcaseView createManual(BadcaseManualRequest request);

    /** 列表（管理页）：origin/domain/status/关键词筛选 + 分页。 */
    PageResult<BadcaseView> list(String origin, String domain, String status, String keyword, int page, int size);

    /** 详情。 */
    BadcaseView get(Long id);

    /** 归因与领域确认（人工）：写入 triage（auto=false，锁定后不再被规则覆盖）；domain 非空则一并更新。 */
    BadcaseView updateTriage(Long id, String stage, String note, String domain);

    /** 忽略 / 恢复（只允许 IGNORED ↔ DRAFT；READY 由转正流程管理）。 */
    BadcaseView updateStatus(Long id, String status);

    /** 删除（撤销误点反馈等）：READY 已是评测条目，拒绝删（去评测数据集处理）。 */
    void delete(Long id);

    /** 分布统计：状态 / 环节 / 领域 / 渠道计数。 */
    BadcaseStats stats();

    /** 领域字典（配置下发，允许字典外自由值）。 */
    List<String> domains();

    /** 批量查已反馈标记（历史消息回填：messageId → badcase 条目 id）。 */
    Map<Long, Long> feedbackFlags(List<Long> messageIds);

    /** 转正辅助：在目标数据集关联集合范围内，用该 bad case 的问题重新检索候选文档。 */
    List<CandidateDoc> searchCandidates(Long id, Long targetDatasetId, Integer topN);

    /** 转正辅助：LLM 基于勾选文档起草标准答案（人工修订后提交转正）。 */
    String draftAnswer(Long id, List<String> docIds);

    /** 转正：补齐 ground truth 后搬到目标领域数据集（READY + enabled=1）。 */
    BadcaseView promote(Long id, BadcasePromoteRequest request);
}
