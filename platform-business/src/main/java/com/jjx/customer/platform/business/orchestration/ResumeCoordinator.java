package com.jjx.customer.platform.business.orchestration;

import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.session.AgentSessionServiceImpl;
import com.jjx.customer.platform.business.session.AgentSessionState;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 会话恢复协调器（自 {@link ChatOrchestrator} 决策链第 0 步拆出）：
 * 查活动追问会话 + 抢占认领，产出本轮是否走恢复路径的判定。
 */
@Component
@RequiredArgsConstructor
public class ResumeCoordinator {

    private static final TelemetryLogger log = TelemetryLogger.of(ResumeCoordinator.class);

    private final AgentSessionServiceImpl sessionStore;

    /** 恢复判定产物：activeSession 非空且 resumed=true 时，本轮消息按会话归属合并槽位继续。 */
    public record ResumeDecision(AgentSessionState activeSession, boolean resumed) {
    }

    /**
     * 查活动会话并抢占认领（决策链第 0 步，优先级最高，先于归一化/意图分类——
     * 否则"prod 环境，接口是 xxx"这类补槽消息会被误判成闲聊/悬空指代）。
     */
    public ResumeDecision findResumable(String conversationId) {
        AgentSessionState activeSession = sessionStore.findActive(conversationId).orElse(null);
        boolean resumed = activeSession != null && sessionStore.claim(conversationId);
        if (resumed) {
            log.info("[对话编排] 恢复追问会话: stage={}, slots={}", activeSession.stage(), activeSession.slots());
        }
        return new ResumeDecision(activeSession, resumed);
    }

    /** 会话恢复的目标 agent：按会话归属路由；agentId 空白/未知回 ops（存量会话全是 ops 的旧数据兼容）。 */
    public String requireResumeTarget(AgentSessionState state) {
        return StringUtils.hasText(state.agentId())
                ? state.agentId() : AgentCatalog.OPS.id();
    }
}
