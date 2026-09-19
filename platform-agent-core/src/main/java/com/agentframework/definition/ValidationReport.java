package com.agentframework.definition;

import java.util.List;

/**
 * 校验报告：聚合一次校验的全部问题，只有存在 {@link ValidationSeverity#ERROR} 时才阻断。
 *
 * @param problems 问题列表
 */
public record ValidationReport(List<ValidationProblem> problems) {

    public ValidationReport {
        problems = List.copyOf(problems == null ? List.of() : problems);
    }

    /** @return 空报告 */
    public static ValidationReport empty() {
        return new ValidationReport(List.of());
    }

    /**
     * @param problems 问题列表
     * @return 报告
     */
    public static ValidationReport of(List<ValidationProblem> problems) {
        return new ValidationReport(problems);
    }

    /**
     * @param problems 问题
     * @return 报告
     */
    public static ValidationReport of(ValidationProblem... problems) {
        return new ValidationReport(problems == null ? List.of() : List.of(problems));
    }

    /** @return 是否存在阻断性问题 */
    public boolean hasErrors() {
        return problems.stream().anyMatch(ValidationProblem::blocking);
    }

    /** @return 是否没有任何问题 */
    public boolean isEmpty() {
        return problems.isEmpty();
    }

    /** @return 阻断性问题 */
    public List<ValidationProblem> errors() {
        return problems.stream().filter(ValidationProblem::blocking).toList();
    }

    /** @return 警告与提示 */
    public List<ValidationProblem> warnings() {
        return problems.stream().filter(problem -> !problem.blocking()).toList();
    }

    /** @return 阻断性问题的说明列表，供旧 API 使用 */
    public List<String> errorMessages() {
        return errors().stream().map(ValidationProblem::message).toList();
    }

    /** @return 全部问题的说明列表，供诊断输出使用 */
    public List<String> messages() {
        return problems.stream().map(ValidationProblem::message).toList();
    }

    /**
     * 合并另一份报告。
     *
     * @param other 另一份报告，可为 null
     * @return 合并后的报告
     */
    public ValidationReport merge(ValidationReport other) {
        if (other == null || other.problems.isEmpty()) {
            return this;
        }
        List<ValidationProblem> merged = new java.util.ArrayList<>(problems);
        merged.addAll(other.problems);
        return new ValidationReport(merged);
    }
}
