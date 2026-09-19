package com.agentframework.definition;

import java.util.List;

/**
 * 定义校验异常：当 {@code Agent}、{@code Workflow} 等定义不满足 schema 或引用约束时抛出。
 *
 * <p>异常中保留全部问题列表，便于一次性暴露所有定义缺陷，而不是逐个报错。</p>
 */
public class DefinitionValidationException extends RuntimeException {

    private final List<String> problems;
    private final ValidationReport report;

    /**
     * @param subject  校验对象描述，例如 {@code workflow 'research@1.0.0'}
     * @param problems 全部校验失败项，至少一项
     */
    public DefinitionValidationException(String subject, List<String> problems) {
        this(subject, ValidationReport.of(problems == null ? List.of() : problems.stream()
                .map(problem -> ValidationProblem.error("VALIDATION_ERROR", subject, problem))
                .toList()));
    }

    /**
     * @param subject 校验对象描述
     * @param report  校验报告
     */
    public DefinitionValidationException(String subject, ValidationReport report) {
        super(buildMessage(subject, report));
        this.report = report == null ? ValidationReport.empty() : report;
        List<String> messages = this.report.errorMessages();
        this.problems = messages.isEmpty() ? this.report.messages() : messages;
    }

    /** @return 不可变的校验失败项列表 */
    public List<String> problems() {
        return problems;
    }

    /** @return 完整校验报告 */
    public ValidationReport report() {
        return report;
    }

    /**
     * 构造异常消息：优先展示阻断性问题，没有阻断项时展示全部问题。
     *
     * @param subject 校验对象
     * @param report  报告
     * @return 异常消息
     */
    private static String buildMessage(String subject, ValidationReport report) {
        ValidationReport effective = report == null ? ValidationReport.empty() : report;
        List<ValidationProblem> problems = effective.hasErrors() ? effective.errors() : effective.problems();
        List<String> messages = problems.stream().map(DefinitionValidationException::describe).toList();
        return subject + " is invalid: " + String.join("; ", messages);
    }

    /**
     * 渲染单条问题：携带错误码，便于从异常消息直接定位规则。
     *
     * @param problem 问题
     * @return 渲染文本
     */
    private static String describe(ValidationProblem problem) {
        return "VALIDATION_ERROR".equals(problem.code())
                ? problem.message()
                : "[" + problem.code() + "] " + problem.message();
    }
}
