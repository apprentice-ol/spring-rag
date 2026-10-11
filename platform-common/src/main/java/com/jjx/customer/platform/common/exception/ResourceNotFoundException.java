package com.jjx.customer.platform.common.exception;

/** 请求的资源不存在（404 语义，common 基础异常）。 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
