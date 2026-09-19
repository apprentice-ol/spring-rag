package com.jjx.customer.platform.business.ops.rest;

import com.jjx.customer.platform.business.ops.OpsRunner;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.config.properties.AgentProperties;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.business.ops.rest.DiagnoseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 日志诊断入口（2026-09-12 框架化批次 2 合流：薄壳化调 {@link OpsDiagnoseAgent}，
 * 删除原 LogDiagnoseService 的独立编排——同一诊断能力单一实现，agent 链路改进自动惠及 REST）。
 *
 * <p><b>响应结构变化</b>（对齐 agent 出参）：旧结构（logs/errors/relatedDocs/suggestion 中间产物）
 * 收敛为结论 + Outcome 类型；完整过程见 agent trace（对话链路 /admin/compare 可查）。
 * REST 为同步低频调试接口：不挂 DegradeGuard、无 SSE、无会话持久化（conversationId 空）。</p>
 *
 * <p>Knife4j 自动生成文档（/api/rag/doc.html）。
 */
@Slf4j
@RestController
@RequestMapping("/diagnose")
@RequiredArgsConstructor
public class DiagnoseController {

    private final OpsRunner frameworkOpsRunner;
    private final ChatProperties chatProperties;
    private final AgentProperties agentProperties;

    /**
     * 按 traceId 诊断（traceId 预填槽位；缺其余必填槽时返回追问说明而非结论）。
     *
     * @param traceId 链路 ID（从日志的 [traceId,spanId] 取）
     */
    @PostMapping("/trace")
    public DiagnoseResponse diagnose(@RequestParam String traceId) {
        // 框架主线：与对话链路同一实现（REST 为单轮：conversationId 为空，不落会话）
        OpsRunner.OpsAnswer answer = frameworkOpsRunner.run("按 traceId 诊断：" + traceId,
                OpsSlotCatalog.sanitized(Map.of(OpsSlotCatalog.TRACE_ID, traceId)), null);
        return new DiagnoseResponse(traceId, answer.text(), answer.kind().name(),
                answer.trace() == null ? 0 : answer.trace().getLlmCallCount());
    }
}
