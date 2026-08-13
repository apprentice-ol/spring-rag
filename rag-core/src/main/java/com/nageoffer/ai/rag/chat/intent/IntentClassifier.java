package com.nageoffer.ai.rag.chat.intent;

/**
 * 用户意图分类器接口。
 * <p>
 * 判断用户提问属于哪种对话场景，从而决定对话管线的行为：
 * <ul>
 *   <li>{@code knowledge_query} — 知识库查询，需要检索后回答</li>
 *   <li>{@code greeting} — 问候语，直接回复无需检索</li>
 *   <li>{@code chitchat} — 闲聊，直接回复无需检索</li>
 * </ul>
 * 简化自 ragent 的 IntentResolver + DefaultIntentClassifier 树形分类体系。
 * </p>
 */
public interface IntentClassifier {

    /**
     * 分类用户问题意图。
     *
     * @param question 用户问题
     * @return 意图分类结果
     */
    IntentResult classify(String question);
}
