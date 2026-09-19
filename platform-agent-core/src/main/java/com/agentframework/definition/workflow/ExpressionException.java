package com.agentframework.definition.workflow;

/** 表达式异常：工作流条件无法解析或求值时抛出。 */
public class ExpressionException extends RuntimeException {

    /** @param message 错误描述 */
    public ExpressionException(String message) {
        super(message);
    }
}
