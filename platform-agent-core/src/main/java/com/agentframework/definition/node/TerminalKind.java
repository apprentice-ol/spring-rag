package com.agentframework.definition.node;

/**
 * 节点终态语义：这个节点走完对调用方意味着什么。
 *
 * <p>出口推导（业务侧 {@code RunOutcomeMapper} 一类）读节点声明而不是靠节点命名约定——
 * 约定（如 {@code escalate_node}）换个节点名就静默失效，声明不会。挂在
 * {@link NodeDefinition#terminalKind()}（meta 属性 {@code terminalKind}）上，
 * 七个节点定义实现零改动。</p>
 *
 * <p>挂起（{@code NodeResult.suspended()}）是结构性信号，不需要也不应该用本枚举表达；
 * 本枚举只补「同为正常走完、业务含义不同」的那一层。</p>
 */
public enum TerminalKind {

    /** 常规收尾：输出即最终答案。 */
    FINISH,

    /** 升级/移交：出口意味着把控制权交给外部（人工、上游系统），输出是移交说明而非答案。 */
    ESCALATE,

    /** 主动征询：出口是向用户提问、等待补充输入。 */
    ASK;

    /** meta 属性键（节点声明处与 {@link NodeDefinition#terminalKind()} 读取处共用）。 */
    public static final String META_KEY = "terminalKind";

    /**
     * @param value meta 属性值（大小写不敏感）
     * @return 对应枚举；null / 空白 / 未知值返回 null（= 节点未声明终态语义）
     */
    public static TerminalKind of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TerminalKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value.trim())) {
                return kind;
            }
        }
        return null;
    }
}
