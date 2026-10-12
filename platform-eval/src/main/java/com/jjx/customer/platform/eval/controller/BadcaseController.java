package com.jjx.customer.platform.eval.controller;

import com.jjx.customer.platform.common.dto.PageResult;
import com.jjx.customer.platform.eval.domain.BadcaseCollectRequest;
import com.jjx.customer.platform.eval.domain.BadcaseManualRequest;
import com.jjx.customer.platform.eval.domain.BadcasePromoteRequest;
import com.jjx.customer.platform.eval.domain.BadcaseStats;
import com.jjx.customer.platform.eval.domain.BadcaseView;
import com.jjx.customer.platform.eval.domain.CandidateDoc;
import com.jjx.customer.platform.eval.service.BadcaseService;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * bad case 归集（聊天页点踩 / 管理页归因与转正）。
 *
 * <p>统一模型：badcase 即 sa_eval_item 的一种，归集池（DRAFT）→ 转正到领域数据集（READY）。
 * 设计见 docs/plans/2026-10-11-badcase-collection.md。</p>
 */
@RestController
@RequestMapping("/eval/badcases")
@RequiredArgsConstructor
public class BadcaseController {

    private final BadcaseService badcaseService;

    /** 归集（幂等 by messageId：重复点踩=更新原因/备注）。 */
    @PostMapping
    public BadcaseView collect(@RequestBody CollectRequest request) {
        return badcaseService.collect(new BadcaseCollectRequest(
                request.getMessageId(), request.getReason(), request.getNote(),
                request.getDomain(), request.getOrigin()));
    }

    /** 手工录入（管理页；无线上现场，证据为空、归因 UNKNOWN）。 */
    @PostMapping("/manual")
    public BadcaseView createManual(@RequestBody ManualRequest request) {
        return badcaseService.createManual(new BadcaseManualRequest(
                request.getQuestion(), request.getNote(), request.getDomain()));
    }

    /** 列表（管理页）：来源/领域/状态/关键词筛选 + 分页。 */
    @GetMapping
    public PageResult<BadcaseView> list(@RequestParam(required = false) String origin,
                                        @RequestParam(required = false) String domain,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return badcaseService.list(origin, domain, status, keyword, page, size);
    }

    /** 分布统计（领域 × 环节），针对性优化的依据。 */
    @GetMapping("/stats")
    public BadcaseStats stats() {
        return badcaseService.stats();
    }

    /** 领域字典（配置下发，允许字典外自由值）。 */
    @GetMapping("/domains")
    public List<String> domains() {
        return badcaseService.domains();
    }

    /** 批量查已反馈标记（历史消息回填：messageId → badcase 条目 id）。 */
    @GetMapping("/feedback-flags")
    public Map<Long, Long> feedbackFlags(@RequestParam List<Long> messageIds) {
        return badcaseService.feedbackFlags(messageIds);
    }

    /** 详情（含证据快照与归因结论）。 */
    @GetMapping("/{id}")
    public BadcaseView get(@PathVariable Long id) {
        return badcaseService.get(id);
    }

    /** 归因与领域确认（人工）：domain 非空则一并更新（点踩时用户答不出领域，此处补）。 */
    @PutMapping("/{id}/triage")
    public BadcaseView updateTriage(@PathVariable Long id, @RequestBody TriageRequest request) {
        return badcaseService.updateTriage(id, request.getStage(), request.getNote(), request.getDomain());
    }

    /** 忽略 / 恢复（IGNORED ↔ DRAFT）。 */
    @PutMapping("/{id}/status")
    public BadcaseView updateStatus(@PathVariable Long id, @RequestBody StatusRequest request) {
        return badcaseService.updateStatus(id, request.getStatus());
    }

    /** 删除（撤销误点反馈 / 清理无效归集）；READY 已是评测条目，拒绝。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        badcaseService.delete(id);
        return Map.of("ok", true);
    }

    /** 转正辅助：在目标数据集关联集合范围内，用该 bad case 的问题重新检索候选文档。 */
    @PostMapping("/{id}/search-candidates")
    public List<CandidateDoc> searchCandidates(@PathVariable Long id,
                                               @RequestBody SearchCandidatesRequest request) {
        return badcaseService.searchCandidates(id, request.getTargetDatasetId(), request.getTopN());
    }

    /** 转正辅助：基于勾选文档起草标准答案（人工修订后提交转正）。 */
    @PostMapping("/{id}/draft-answer")
    public Map<String, Object> draftAnswer(@PathVariable Long id, @RequestBody DraftAnswerRequest request) {
        return Map.of("answer", badcaseService.draftAnswer(id, request.getDocIds()));
    }

    /** 转正：补 ground truth → 搬到目标领域数据集（READY + enabled=1，下轮跑批自动参与）。 */
    @PostMapping("/{id}/promote")
    public BadcaseView promote(@PathVariable Long id, @RequestBody PromoteRequest request) {
        return badcaseService.promote(id, new BadcasePromoteRequest(
                request.getTargetDatasetId(), request.getExpectedDocIds(),
                request.getExpectedAnswer(), request.getDomain()));
    }

    @Data
    public static class CollectRequest {
        private Long messageId;
        private String reason;
        private String note;
        private String domain;
        private String origin;
    }

    @Data
    public static class TriageRequest {
        private String stage;
        private String note;
        private String domain;
    }

    @Data
    public static class StatusRequest {
        private String status;
    }

    @Data
    public static class ManualRequest {
        private String question;
        private String note;
        private String domain;
    }

    @Data
    public static class SearchCandidatesRequest {
        private Long targetDatasetId;
        private Integer topN;
    }

    @Data
    public static class DraftAnswerRequest {
        private List<String> docIds;
    }

    @Data
    public static class PromoteRequest {
        private Long targetDatasetId;
        private List<String> expectedDocIds;
        private String expectedAnswer;
        private String domain;
    }
}
