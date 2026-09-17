package com.jjx.customer.platform.agent.framework.workflow;

import java.util.List;
import java.util.Optional;

/**
 * Workflow 目录：id → Workflow（代码注册，配置只做选择）。
 */
public interface WorkflowCatalog {

    Optional<Workflow> byId(String workflowId);

    List<Workflow> all();
}
