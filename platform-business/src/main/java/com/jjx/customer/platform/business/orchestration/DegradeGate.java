package com.jjx.customer.platform.business.orchestration;

import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 降级闸统一入口（自 {@link ChatOrchestrator} 拆出）：acquire 失败即替调用方发出拒绝提示，
 * 返回 null = 已拒绝，调用方直接 return。ops 分支 / RAG 流式 / 闲聊三处共用同一闸门语义。
 */
@Component
@RequiredArgsConstructor
public class DegradeGate<S> {

    private final DegradeGuard degradeGuard;
    private final DeliveryPortFactory<S> deliveryPortFactory;

    /**
     * 尝试拿降级 lease；拿不到时发出降级提示（礼貌拒绝，不跑 LLM）。
     *
     * @return lease（调用方负责 close）；null = 已拒绝并已发送提示，调用方直接 return
     */
    public DegradeGuard.Lease tryAcquire(String conversationId, S sink, String otelTraceId) {
        DegradeGuard.Lease lease = degradeGuard.tryAcquire().orElse(null);
        if (lease == null) {
            deliveryPortFactory.begin(sink, conversationId, null, otelTraceId, null, null)
                    .emitDegraded(conversationId, otelTraceId);
        }
        return lease;
    }
}
