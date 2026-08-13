package com.nageoffer.ai.rag.ingestion.engine;

import lombok.Data;

/**
 * 节点执行结果。
 *
 * <p>搬自原 ragent ingestion/domain/result/NodeResult.java：success 控制是否失败终止，
 * shouldContinue 控制是否继续下一节点（skip/terminate 时为 false）。
 */
@Data
public class NodeResult {

    private final boolean success;
    private final boolean shouldContinue;
    private final String message;
    private final Throwable error;

    private NodeResult(boolean success, boolean shouldContinue, String message, Throwable error) {
        this.success = success;
        this.shouldContinue = shouldContinue;
        this.message = message;
        this.error = error;
    }

    public static NodeResult ok(String message) {
        return new NodeResult(true, true, message, null);
    }

    public static NodeResult skip(String reason) {
        return new NodeResult(true, false, reason, null);
    }

    public static NodeResult terminate(String reason) {
        return new NodeResult(true, false, reason, null);
    }

    public static NodeResult fail(Throwable error) {
        return new NodeResult(false, false, error.getMessage(), error);
    }
}
