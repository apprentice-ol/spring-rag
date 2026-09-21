package com.jjx.customer.platform.business.knowledge.intent;
import com.jjx.customer.platform.intent.IntentResult;

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

    /**
     * 带最近对话历史分类：多轮追问常是裸关键词（"Refresh-Token"），脱离历史只能瞎猜——
     * 实测被猜成"疑似排查令牌报错"劫持进诊断（2026-09-20，confidence 正好卡 0.6 门槛）。
     * 历史仅供判断话题延续，不改变"以当前问题为主"的归类原则。
     *
     * @param question      用户问题
     * @param recentHistory 最近对话（可为空串 = 首轮，等价单参版）
     * @return 意图分类结果
     */
    default IntentResult classify(String question, String recentHistory) {
        return classify(question);
    }
}
