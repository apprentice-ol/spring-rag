package com.nageoffer.ai.rag.common.context;

/** 用户上下文占位（srag 无登录态，统一返回 "system"）。后续接 Sa-Token 时改为真实用户。 */
public final class UserContext {

    private UserContext() {
    }

    public static String getUsername() {
        return "system";
    }
}
