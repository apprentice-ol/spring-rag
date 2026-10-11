package com.jjx.customer.platform.web;

import com.jjx.customer.platform.common.exception.ClientException;
import com.jjx.customer.platform.common.exception.ResourceNotFoundException;
import com.jjx.customer.platform.common.exception.ServiceException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
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

    // ==================== 缺少必填参数（400） ====================

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[全局异常] 缺少必填参数: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", "缺少必填参数: " + e.getParameterName()));
    }

    // ==================== 上传缺少部件（400） ====================

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("[全局异常] 上传缺少部件: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", "缺少上传文件部件: " + e.getRequestPartName()));
    }

    // ==================== 请求体不可读（400） ====================

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("[全局异常] 请求体不可读: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", "请求体格式错误（JSON 解析失败）"));
    }

    // ==================== 参数类型不匹配（400） ====================

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[全局异常] 参数类型不匹配: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", "参数类型错误: " + e.getName()));
    }

    // ==================== 请求方法不支持（405） ====================

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[全局异常] 请求方法不支持: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("code", 405, "message", "该接口不支持 " + e.getMethod() + " 方法"));
    }

    // ==================== Content-Type 不支持（415） ====================

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("[全局异常] Content-Type 不支持: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("code", 415, "message", "不支持的 Content-Type: " + e.getContentType()));
    }

    // ==================== 资源不存在（404） ====================

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(ResourceNotFoundException e) {
        log.debug("[全局异常] 资源不存在: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", 404, "message", e.getMessage()));
    }

    // ==================== 唯一约束冲突（409） ====================

    /** 从 PG 报文 "Key (name)=(冒烟测试) already exists" 提取友好提示，失败退通用文案。 */
    private static final Pattern DUPLICATE_KEY_DETAIL = Pattern.compile("Key \\((\\w+)\\)=\\((.+?)\\)");

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(DuplicateKeyException e) {
        Matcher m = DUPLICATE_KEY_DETAIL.matcher(String.valueOf(e.getMessage()));
        String msg = m.find()
                ? m.group(1) + "「" + m.group(2) + "」已存在，请换一个"
                : "资源已存在（唯一约束冲突）";
        log.warn("[全局异常] 唯一约束冲突: {}", msg);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("code", 409, "message", msg));
    }

    // ==================== 数据完整性违例（400） ====================

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("[全局异常] 数据完整性违例: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(Map.of("code", 400, "message", "数据不合法（必填字段为空/超长等违反完整性约束）"));
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
