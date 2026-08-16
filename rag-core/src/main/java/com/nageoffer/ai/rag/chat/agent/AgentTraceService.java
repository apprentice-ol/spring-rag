package com.nageoffer.ai.rag.chat.agent;

import com.nageoffer.ai.rag.chat.dao.entity.AgentTraceEntity;
import com.nageoffer.ai.rag.ingestion.domain.dto.PageResult;
import java.util.List;
import java.util.Map;

/** Agent 执行轨迹服务：线上 chat 落库 + 管理后台查询分析。 */
public interface AgentTraceService {

    /** 落一条轨迹（对话流程异步调用，失败不影响对话）。messageId 关联 assistant 消息，traceId 为本次请求 OTel traceId，均可空。 */
    void record(String conversationId, Long messageId, String paradigm, String question, AgentTrace trace, String traceId);

    /** 按关联的 assistant 消息 id 查最近一条轨迹（对话页历史消息回看轨迹用），无则 null。 */
    AgentTraceEntity getByMessageId(Long messageId);

    /** 分页列表（可按 paradigm / question 关键词过滤）。 */
    PageResult<AgentTraceEntity> page(int page, int size, String paradigm, String keyword);

    /** 单条详情（含 steps）。 */
    AgentTraceEntity get(Long id);

    /** 按 paradigm 分组统计：count + 平均步数。 */
    List<Map<String, Object>> stats();
}
