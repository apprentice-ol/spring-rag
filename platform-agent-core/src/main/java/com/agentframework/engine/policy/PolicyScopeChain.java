package com.agentframework.engine.policy;

import java.util.List;

/**
 * 作用域链：由外到内排列，解析器按此顺序合并声明。
 *
 * @param scopes 作用域列表
 */
public record PolicyScopeChain(List<PolicyScope> scopes) {

    public PolicyScopeChain {
        scopes = List.copyOf(scopes == null ? List.of() : scopes);
    }

    /**
     * @param scopes 作用域，按由外到内顺序
     * @return 作用域链
     */
    public static PolicyScopeChain of(PolicyScope... scopes) {
        return new PolicyScopeChain(scopes == null ? List.of() : List.of(scopes));
    }

    /** @return 链上最内侧的作用域，链为空时返回 null */
    public PolicyScope innermost() {
        return scopes.isEmpty() ? null : scopes.get(scopes.size() - 1);
    }
}
