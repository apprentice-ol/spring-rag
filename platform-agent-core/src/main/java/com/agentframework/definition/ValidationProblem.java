package com.agentframework.definition;

import java.util.List;

/**
 * 单条校验问题：机器可读的定位 + 人类可读的说明。
 *
 * @param code       机器可读错误码，例如 {@code POLICY_REF_UNKNOWN}
 * @param severity   严重级
 * @param location   位置，例如 {@code workflow:pipeline@1.0.0/node:plan/meta.guardRefs[0]}
 * @param message    说明，含候选与修复建议
 * @param candidates 拼写候选，可为空
 */
public record ValidationProblem(
        String code,
        ValidationSeverity severity,
        String location,
        String message,
        List<String> candidates) {

    public ValidationProblem {
        code = code == null || code.isBlank() ? "VALIDATION_ERROR" : code;
        severity = severity == null ? ValidationSeverity.ERROR : severity;
        location = location == null ? "" : location;
        message = message == null ? "" : message;
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    /**
     * @param code     错误码
     * @param location 位置
     * @param message  说明
     * @return 错误项
     */
    public static ValidationProblem error(String code, String location, String message) {
        return new ValidationProblem(code, ValidationSeverity.ERROR, location, message, null);
    }

    /**
     * @param code     错误码
     * @param location 位置
     * @param message  说明
     * @return 警告项
     */
    public static ValidationProblem warning(String code, String location, String message) {
        return new ValidationProblem(code, ValidationSeverity.WARNING, location, message, null);
    }

    /**
     * @param code     错误码
     * @param location 位置
     * @param message  说明
     * @return 提示项
     */
    public static ValidationProblem info(String code, String location, String message) {
        return new ValidationProblem(code, ValidationSeverity.INFO, location, message, null);
    }

    /**
     * @param candidates 候选清单
     * @return 追加候选后的条目
     */
    public ValidationProblem withCandidates(List<String> candidates) {
        return new ValidationProblem(code, severity, location, message, candidates);
    }

    /** @return 是否阻断加载 */
    public boolean blocking() {
        return severity == ValidationSeverity.ERROR;
    }
}
