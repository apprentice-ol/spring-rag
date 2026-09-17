package com.jjx.customer.platform.delivery.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动对话流注册表（conversationId → 取消句柄）：支撑「停止生成」。
 *
 * <p>交付端在请求入口注册初始句柄（关交付流，覆盖追问/ops/降级等早期阶段），进入流式回答段后
 * 升级为完全体句柄（dispose LLM 流 + 部分回答落库 + 关交付流），请求收尾注销。
 * {@code POST /chat/cancel} 按会话取消——与前端 abort 本地 fetch 双保险：服务端取消能
 * 干净地完成流（前端按正常流结束处理），并在断连检测之外立即停掉 token 计费。</p>
 *
 * <p>单会话单活动流（同会话并发两条流本身是病态场景，后注册者覆盖前者）。</p>
 */
@Slf4j
@Component
public class ActiveStreamRegistry {

    private final ConcurrentHashMap<String, Runnable> active = new ConcurrentHashMap<>();

    /** 注册（同会话重复注册 = 覆盖为最新流的取消句柄）。 */
    public void register(String conversationId, Runnable cancel) {
        active.put(conversationId, cancel);
    }

    /** 请求收尾注销（不触发取消）。 */
    public void unregister(String conversationId) {
        active.remove(conversationId);
    }

    /** 取消该会话的活动流（存在则执行并注销）。 */
    public boolean cancel(String conversationId) {
        Runnable cancel = active.remove(conversationId);
        if (cancel == null) {
            return false;
        }
        log.info("[对话取消] conversationId={}", conversationId);
        cancel.run();
        return true;
    }
}
