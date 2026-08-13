package com.nageoffer.ai.rag.web;

import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.common.exception.ServiceException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器。
 * <p>
 * 统一拦截 Controller 层抛出的异常，返回结构化的错误响应。
 * 涵盖：上传文件过大、资源不存在、参数校验失败、ClientException、ServiceException 等。
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ==================== 资源不存在（404） ====================

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException e) {
        log.debug("[全局异常] 资源不存在: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", "接口不存在: " + e.getResourcePath()));
    }

    // ==================== 上传文件过大（413） ====================

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        long maxSize = e.getMaxUploadSize();
        String maxSizeStr = maxSize > 0
                ? formatSize(maxSize)
                : "未知";
        String msg = "上传文件超过大小限制（最大 " + maxSizeStr + "）";

        log.warn("[全局异常] 上传文件过大: limit={}, {}", maxSizeStr, e.getMessage());
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(
                        "code", 413,
                        "message", msg,
                        "maxUploadSize", maxSizeStr
                ));
    }

    // ==================== 参数校验失败（400） ====================

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[全局异常] 参数错误: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", e.getMessage()));
    }

    // ==================== 业务客户端异常（4xx） ====================

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<Map<String, Object>> handleClientException(ClientException e) {
        log.warn("[全局异常] 客户端异常: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", e.getMessage()));
    }

    // ==================== 服务端异常（500） ====================

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Map<String, Object>> handleServiceException(ServiceException e) {
        log.error("[全局异常] 服务异常: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", 500, "message", e.getMessage()));
    }

    // ==================== 兜底异常（500） ====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("[全局异常] 未捕获异常: {}", e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", 500, "message", "服务器内部错误: " + e.getMessage()));
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
