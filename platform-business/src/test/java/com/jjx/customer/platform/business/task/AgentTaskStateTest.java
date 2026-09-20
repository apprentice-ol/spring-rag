package com.jjx.customer.platform.business.task;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务契约的派生规则与状态语义。
 *
 * <p>守的是这次改造的<b>支点</b>：引擎会话 id 从 {@code "ops-" + conversationId} 换成
 * {@code "ops-" + taskId + "#" + attemptNo}。旧规则下同 id 反复 startSession 会**覆盖**，
 * 每一次收尾都在销毁上一轮的过程记录（scratchpad / 阶段产出 / 预算计数）——
 * 这正是"诊断出结论后追问，上下文全丢"的机制。</p>
 */
class AgentTaskStateTest {

    @Test
    void 引擎会话id按任务与attempt序号派生() {
        assertEquals("ops-task-1#1", AgentTaskState.attemptIdOf("task-1", 1));
        assertEquals("ops-task-1#3", AgentTaskState.attemptIdOf("task-1", 3));
    }

    @Test
    void 同一任务的相邻attempt会话id必须不同() {
        // 这条是"不覆盖"的全部保证：只要 id 不同，引擎就是两个会话，旧的不会被写掉
        assertNotEquals(AgentTaskState.attemptIdOf("task-1", 1),
                AgentTaskState.attemptIdOf("task-1", 2),
                "相邻 attempt 的引擎会话 id 相同 = 旧 attempt 会被覆盖，改造即失效");
    }

    @Test
    void 不同任务的会话id不会相撞() {
        assertNotEquals(AgentTaskState.attemptIdOf("task-1", 1),
                AgentTaskState.attemptIdOf("task-2", 1));
    }

    @Test
    void 尚无attempt时按第1次算() {
        assertEquals("ops-task-1#1", task(AgentTaskState.Status.OPEN, 0).currentAttemptId());
    }

    @Test
    void 挂起中的任务指向它自己的attempt() {
        assertEquals("ops-task-1#2", task(AgentTaskState.Status.SUSPENDED, 2).currentAttemptId());
    }

    @Test
    void 已出结论与尚未开跑的任务仍可沿用() {
        // CONCLUDED 必须可沿用——这是"收尾后追问答得上"的状态依据
        assertTrue(task(AgentTaskState.Status.CONCLUDED, 1).reusable());
        assertTrue(task(AgentTaskState.Status.OPEN, 0).reusable());
        assertTrue(task(AgentTaskState.Status.SUSPENDED, 1).reusable());
    }

    @Test
    void 进行中与已关闭的任务不可沿用() {
        // RUNNING 不可沿用：那是并发保护，见 AgentTaskMapper.beginAttempt 的状态条件
        assertFalse(task(AgentTaskState.Status.RUNNING, 1).reusable());
        assertFalse(task(AgentTaskState.Status.CLOSED, 2).reusable());
        assertFalse(task(AgentTaskState.Status.ABANDONED, 2).reusable());
    }

    private static AgentTaskState task(AgentTaskState.Status status, int attemptCount) {
        return new AgentTaskState("task-1", "conv-1", "ops_diagnose", "inv_think",
                status, Map.of("interface", "/api/invoice/reverse"), null, null, null, attemptCount);
    }
}
