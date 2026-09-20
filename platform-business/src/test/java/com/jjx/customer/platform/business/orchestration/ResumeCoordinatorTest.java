package com.jjx.customer.platform.business.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.task.AgentTaskServiceImpl;
import com.jjx.customer.platform.business.task.AgentTaskState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由判定：本轮消息归不归既有诊断任务。
 *
 * <p>守的是**真机踩到的一个坑**：早先只认 {@code SUSPENDED}，已出结论（{@code CONCLUDED}）的任务
 * 交给意图分类路由。实测用户回"我不同意某条风险"时，消息落进 RAG 线，答成
 * "现有资料中没有这条结论"——诊断上下文一点没用上，正是本次改造要消除的体验。</p>
 *
 * <p>{@code CONCLUDED} 在状态机里的定义就是"已出结论、等用户反应"，把它当普通新问题路由与定义相悖。</p>
 */
class ResumeCoordinatorTest {

    private static final String TRACE_A = "4fbdabbe672b82934aa42c6664d367d2";
    private static final String TRACE_B = "aabbccddeeff00112233445566778899";
    private static final String IFACE = "/api/invoice/reverse";

    /** 只保留 findActive 的内存实现（其余方法本测试用不到）。 */
    private static final class InMemoryTaskService extends AgentTaskServiceImpl {
        private AgentTaskState current;

        InMemoryTaskService(AgentTaskState current) {
            super(null, new ObjectMapper(), null);
            this.current = current;
        }

        @Override
        public Optional<AgentTaskState> findActive(String conversationId) {
            return current == null || !current.reusable() ? Optional.empty() : Optional.of(current);
        }
    }

    private static ResumeCoordinator coordinatorOf(AgentTaskState task) {
        return new ResumeCoordinator(new InMemoryTaskService(task));
    }

    private static AgentTaskState task(AgentTaskState.Status status, Map<String, String> slots) {
        return new AgentTaskState("t1", "c1", "ops_diagnose", "confirm_slots",
                status, slots, null, null, null, 1);
    }

    private static Map<String, String> slots() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("interface", IFACE);
        s.put("trace_id", TRACE_A);
        s.put("environment", "prod");
        return s;
    }

    @Test
    void 挂起中的任务无条件归它() {
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.SUSPENDED, slots()));

        // 哪怕用户这一句看着像在问别的，挂起态就是"在等你答上一轮"
        assertTrue(c.findResumable("c1", "顺便问下另一个接口 /api/order/create 怎么调").continueTask());
    }

    @Test
    void 已出结论的追问默认归它() {
        // 真机踩坑点：这句若不归诊断任务，会被 RAG 线接走答成"资料里没有这条结论"
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, slots()));

        assertTrue(c.findResumable("c1", "我不同意「避免重复红冲」这条风险，请据此修正结论").continueTask(),
                "对结论的异议必须回到诊断任务，不能交给意图分类");
    }

    @Test
    void 已出结论但本轮是同一个traceId仍然归它() {
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, slots()));

        assertTrue(c.findResumable("c1", "traceId " + TRACE_A + " 再确认一下").continueTask());
    }

    @Test
    void 已出结论但本轮换了traceId算新目标() {
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, slots()));

        assertFalse(c.findResumable("c1", "traceId " + TRACE_B + " 帮我看看这个").continueTask(),
                "不同的 traceId = 另一件事，交回意图分类");
    }

    @Test
    void 已出结论但本轮换了接口算新目标() {
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, slots()));

        assertFalse(c.findResumable("c1", "/api/order/create 这个接口也报错了").continueTask());
    }

    @Test
    void 本轮提到的是同一个接口则不算新目标() {
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, slots()));

        assertTrue(c.findResumable("c1", IFACE + " 的 invoiceCode 到底怎么填").continueTask());
    }

    @Test
    void 无活动任务时不短路() {
        ResumeCoordinator c = coordinatorOf(null);

        assertFalse(c.findResumable("c1", "随便问个问题").continueTask());
    }

    @Test
    void 已关闭或放弃的任务不再沿用() {
        assertFalse(coordinatorOf(task(AgentTaskState.Status.CLOSED, slots()))
                .findResumable("c1", "还有后续问题").continueTask());
        assertFalse(coordinatorOf(task(AgentTaskState.Status.ABANDONED, slots()))
                .findResumable("c1", "还有后续问题").continueTask());
    }

    @Test
    void 任务上的关键数据缺失时不误判为新目标() {
        // 任务里没记 traceId/接口（降级路径），无从比较——按"沿用"处理，宁可多带不可丢
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, Map.of()));

        assertTrue(c.findResumable("c1", "traceId " + TRACE_B + " 这个呢").continueTask());
    }

    @Test
    void 空消息不误判() {
        ResumeCoordinator c = coordinatorOf(task(AgentTaskState.Status.CONCLUDED, slots()));

        assertTrue(c.findResumable("c1", null).continueTask());
        assertTrue(c.findResumable("c1", "   ").continueTask());
    }

    @Test
    void 判定产物带回活动任务供调用方取槽位() {
        AgentTaskState t = task(AgentTaskState.Status.CONCLUDED, slots());
        ResumeCoordinator.ResumeDecision d = coordinatorOf(t).findResumable("c1", "结论不对");

        assertEquals(t.taskId(), d.activeTask().taskId());
        assertEquals(IFACE, d.activeTask().slots().get("interface"), "追问轮要靠它预填槽位");
    }
}
