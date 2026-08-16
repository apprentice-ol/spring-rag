package com.nageoffer.ai.rag.chat.agent;

import com.nageoffer.ai.rag.chat.dao.entity.AgentTraceEntity;
import com.nageoffer.ai.rag.ingestion.domain.dto.PageResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 执行轨迹查询（管理后台「Agent 轨迹」分析页用）。
 * <p>列表 / 详情 / 统计。轨迹由 {@link com.nageoffer.ai.rag.chat.service.pipeline.StreamChatPipeline}
 * 在线上 chat 走完 agent 后落 sa_agent_trace。</p>
 */
@RestController
@RequestMapping("/agent/traces")
@RequiredArgsConstructor
public class AgentTraceController {

    private final AgentTraceService agentTraceService;

    /** 分页列表（可按 paradigm / question 关键词过滤）。 */
    @GetMapping
    public PageResult<AgentTraceEntity> page(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String paradigm,
                                             @RequestParam(required = false) String keyword) {
        return agentTraceService.page(page, size, paradigm, keyword);
    }

    /** 按 paradigm 分组统计：条数 + 平均步数。 */
    @GetMapping("/stats")
    public List<Map<String, Object>> stats() {
        return agentTraceService.stats();
    }

    /** 按关联的 assistant 消息 id 查最近一条轨迹（对话页气泡「查看轨迹」用），无关联返回 null。 */
    @GetMapping("/by-message")
    public AgentTraceEntity getByMessage(@RequestParam Long messageId) {
        return agentTraceService.getByMessageId(messageId);
    }

    /** 单条详情（含 steps）。 */
    @GetMapping("/{id}")
    public AgentTraceEntity get(@PathVariable Long id) {
        return agentTraceService.get(id);
    }
}
