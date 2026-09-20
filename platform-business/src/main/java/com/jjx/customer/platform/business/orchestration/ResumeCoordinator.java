package com.jjx.customer.platform.business.orchestration;

import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.task.AgentTaskServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskState;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 任务恢复协调器（自 {@link ChatOrchestrator} 决策链第 0 步拆出）：
 * 查活动任务，产出本轮是否走恢复路径的判定。
 *
 * <p>只读不抢占——抢占已下移到执行侧（{@code OpsRunner} 紧挨真正的运行），
 * 这样"占了位却没跑起来"就不再是一个需要额外释放的中间状态。</p>
 */
@Component
@RequiredArgsConstructor
public class ResumeCoordinator {

    private static final TelemetryLogger log = TelemetryLogger.of(ResumeCoordinator.class);

    private final AgentTaskServiceImpl taskService;

    /** 路由判定产物：activeTask 非空且 continueTask=true 时，本轮消息按任务归属合并槽位继续。 */
    public record ResumeDecision(AgentTaskState activeTask, boolean continueTask) {
    }


    /**
     * 查活动任务并判定本轮消息是否归它（决策链第 0 步，优先级最高，先于归一化/意图分类——
     * 否则"prod 环境，接口是 xxx"这类补槽消息会被误判成闲聊/悬空指代）。
     *
     * <p><b>SUSPENDED</b>：明确在等用户回答，当然归它。<br>
     * <b>CONCLUDED</b>：已出结论等用户反应，<b>默认也归它</b>。</p>
     *
     * <p>⚠️ CONCLUDED 这一条是<b>真机踩坑后补上的</b>：早先只认 SUSPENDED，理由是"用户下一句可能
     * 在问别的"。实测该理由站不住——用户回"我不同意某条风险"时，消息落进意图分类被 <b>RAG 线接走</b>，
     * 答成"现有资料中没有这条结论"，诊断上下文一点没用上。状态机里 CONCLUDED 的定义本就是
     * "已出结论、等用户反应"，把它当普通新问题路由与定义相悖。</p>
     *
     * <p><b>例外（新目标强信号）</b>：本轮消息里出现与任务已确认值<b>不同</b>的 traceId 或接口路径，
     * 说明是另一件事，交回意图分类。</p>
     *
     * <p>抢占已移到执行侧（OpsRunner 紧挨着真正的运行），本方法只读不写——
     * 抢在决策步会让"占了位却没跑起来"成为一个需要额外释放的状态。</p>
     *
     * @param conversationId 对话标识
     * @param question       本轮用户消息（判定新目标强信号用，可空）
     */
    public ResumeDecision findResumable(String conversationId, String question) {
        AgentTaskState activeTask = taskService.findActive(conversationId).orElse(null);
        boolean continueTask = activeTask != null && shouldContinue(activeTask, question);
        if (continueTask) {
            log.info("[对话编排] 本轮归入既有任务({}): stage={}, slots={}",
                    activeTask.status(), activeTask.stage(), activeTask.slots());
        }
        return new ResumeDecision(activeTask, continueTask);
    }

    /**
     * 本轮消息是否归该任务。
     *
     * @param task     活动任务
     * @param question 本轮消息
     * @return true = 短路路由到该任务的 agent
     */
    private boolean shouldContinue(AgentTaskState task, String question) {
        if (task.status() == AgentTaskState.Status.SUSPENDED) {
            return true;
        }
        if (task.status() != AgentTaskState.Status.CONCLUDED) {
            return false;
        }
        return !looksLikeNewTarget(task, question);
    }

    /**
     * 新目标强信号：消息里的 traceId／接口路径与任务已确认值不一致（确定性判据，不耗模型）。
     *
     * <p>判据实现归 {@link OpsSlotCatalog#targetShifted}——{@code OpsRunner} 清旧目标槽位时
     * 用的是同一份，避免"路由说是新目标、槽位却没清"这类两处漂移。</p>
     */
    private static boolean looksLikeNewTarget(AgentTaskState task, String question) {
        return OpsSlotCatalog.targetShifted(task.slots(), question);
    }

    /** 恢复的目标 agent：按任务归属路由；agentId 空白/未知回 ops（存量旧数据兼容）。 */
    public String requireResumeTarget(AgentTaskState state) {
        return StringUtils.hasText(state.agentId())
                ? state.agentId() : AgentCatalog.OPS.id();
    }
}
