package com.nageoffer.ai.rag.common.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * 消息体包装器。
 * <p>生产者发消息时用此包装业务载荷，消费者收到后通过 {@link #getBody()} 获取业务对象。</p>
 *
 * @param <T> 业务载荷类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageWrapper<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务 key（如 docId/taskId），可用于幂等判断 */
    private String keys;

    /** 业务载荷 */
    private T body;

    /** 唯一标识，用于客户端幂等验证 */
    @Builder.Default
    private String uuid = UUID.randomUUID().toString();

    /** 消息发送时间戳 */
    @Builder.Default
    private Long timestamp = System.currentTimeMillis();
}
