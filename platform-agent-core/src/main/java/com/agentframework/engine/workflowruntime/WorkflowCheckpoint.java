package com.agentframework.engine.workflowruntime;

import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.slot.Slots;

/**
 * 检查点回调：工作流每推进一步就持久化游标与槽位。
 *
 * <p>断点续跑依赖它：进程崩溃后，新进程可以从最后一个检查点继续。</p>
 */
@FunctionalInterface
public interface WorkflowCheckpoint {

    /**
     * 保存检查点。
     *
     * @param session 会话
     * @param cursor  当前游标
     * @param slots   槽位容器
     */
    void save(Session session, Cursor cursor, Slots slots);
}
