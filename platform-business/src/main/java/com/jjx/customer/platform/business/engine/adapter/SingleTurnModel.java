package com.jjx.customer.platform.business.engine.adapter;

/**
 * 单轮问答的最小模型接口（生产环境由模型网关适配器实现，测试可注入替身）。
 *
 * <p>原先作为 {@code OpsSlotExtractor.Model} 嵌套在 ops 抽槽器里，导致
 * {@code GatewayModelAdapter}（engine 适配层）反向依赖 ops 域；提到 engine/adapter
 * 后依赖方向归正：ops 抽槽/裁决节点与 engine 适配器都只认这个最小接口。</p>
 */
@FunctionalInterface
public interface SingleTurnModel {

    /**
     * @param system 系统提示
     * @param user   用户提示
     * @return 模型输出文本
     */
    String ask(String system, String user);
}
