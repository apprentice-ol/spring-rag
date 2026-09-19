package com.agentframework.engine.workflowruntime;

import com.agentframework.engine.core.RunOutcome;
import com.agentframework.engine.core.WorkflowExecution;

/**
 * 工作流运行时：从游标处驱动节点执行，直到完成、挂起或失败。
 *
 * <p>它只认定义层的节点与边，节点内部实现由 {@code NodeExecutor} 决定。</p>
 */
public interface WorkflowRuntime {

    /**
     * 执行工作流。
     *
     * @param execution 执行输入
     * @return 运行结果
     */
    RunOutcome run(WorkflowExecution execution);
}
