package com.jjx.customer.platform.eval.domain;

/**
 * bad case 归集请求。
 *
 * @param messageId assistant 消息 id（SSE meta.messageId），必填
 * @param reason    点踩原因（answer_irrelevant / content_wrong / incomplete / not_found / other；
 *                  评测低分/巡检等渠道可不填）
 * @param note      备注（可选）
 * @param domain    知识领域（管理页手工录入/巡检可填；聊天点踩时为空，归因确认时补）
 * @param origin    归集渠道（空 = online_dislike）
 */
public record BadcaseCollectRequest(
        Long messageId,
        String reason,
        String note,
        String domain,
        String origin
) {
}
