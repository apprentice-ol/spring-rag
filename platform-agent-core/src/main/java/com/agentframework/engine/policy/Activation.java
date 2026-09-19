package com.agentframework.engine.policy;

/**
 * 组件激活语义：把“注册”与“生效”分开。
 */
public enum Activation {

    /** 注册即生效，可被下层作用域禁用。 */
    DEFAULT_ON,

    /** 仅在显式引用时生效（opt-in）。 */
    DEFAULT_OFF,

    /** 永远生效，任何作用域都不得禁用或替换移除。 */
    MANDATORY
}
