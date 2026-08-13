package com.nageoffer.ai.rag.common.exception;

/** 客户端参数/状态错误（common 基础异常，原 ragent framework 三级异常的简化版）。 */
public class ClientException extends RuntimeException {

    public ClientException(String message) {
        super(message);
    }

    public ClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
