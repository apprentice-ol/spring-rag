package com.agentframework.definition;

/**
 * 校验问题严重级：只有 {@link #ERROR} 会阻断定义加载。
 */
public enum ValidationSeverity {

    /** 阻断性错误：定义不可用。 */
    ERROR,

    /** 警告：定义可用，但存在可疑写法。 */
    WARNING,

    /** 提示：只读建议，不改变任何行为。 */
    INFO
}
