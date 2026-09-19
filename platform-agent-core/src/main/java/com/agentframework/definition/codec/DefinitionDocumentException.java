package com.agentframework.definition.codec;

/**
 * 定义文档异常：解析或编解码失败时抛出，携带机器可读错误码。
 */
public class DefinitionDocumentException extends RuntimeException {

    private final String code;

    /**
     * @param code    错误码
     * @param message 说明
     */
    public DefinitionDocumentException(String code, String message) {
        super(message);
        this.code = code == null ? "DEFINITION_PARSE_ERROR" : code;
    }

    /**
     * @param code  错误码
     * @param message 说明
     * @param cause 原因
     */
    public DefinitionDocumentException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? "DEFINITION_PARSE_ERROR" : code;
    }

    /** @return 错误码 */
    public String code() {
        return code;
    }
}
