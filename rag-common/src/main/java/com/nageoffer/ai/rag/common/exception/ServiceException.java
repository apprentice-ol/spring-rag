package com.nageoffer.ai.rag.common.exception;

/** 服务端业务异常（common 基础异常）。 */
public class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
