package com.agentframework.infra.modelgateway;

import java.util.List;

/**
 * 模型提供方扩展点：对接具体厂商的适配器。
 *
 * <p>内核只认识 {@link ModelGateway}，厂商差异全部收敛在实现里。</p>
 */
public interface ModelProvider {

    /** @return 提供方标识，对应 {@code ModelConfig.provider} */
    String id();

    /** @return 支持的模型名列表，空列表表示全部支持 */
    default List<String> models() {
        return List.of();
    }

    /**
     * 执行一次补全调用。
     *
     * @param request 模型请求
     * @param context 调用上下文
     * @return 模型结果
     */
    ModelResponse complete(ModelRequest request, ModelCallContext context);

    /**
     * 执行一次流式补全：逐段回调 {@code handler}，阻塞到流结束后返回聚合结果（含 usage）。
     *
     * <p>默认退化为 {@link #complete}，把完整内容作为单次 token 回调——不支持流式的提供方
     * 零改动即兼容，上层订阅方可预测至少收到一次回调。</p>
     *
     * @param request 模型请求
     * @param context 调用上下文
     * @param handler token 回调，可为 null（等价于不流式）
     * @return 聚合后的完整模型结果
     */
    default ModelResponse stream(ModelRequest request, ModelCallContext context, StreamHandler handler) {
        ModelResponse response = complete(request, context);
        if (handler != null && response != null && !response.content().isEmpty()) {
            handler.onToken(response.content());
        }
        return response;
    }
}
