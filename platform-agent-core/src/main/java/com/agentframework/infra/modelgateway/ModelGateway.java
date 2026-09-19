package com.agentframework.infra.modelgateway;

/**
 * 模型网关：内核访问模型的唯一入口。
 *
 * <p>负责路由到具体 {@link ModelProvider}，并承担指标等横切职责。</p>
 */
public interface ModelGateway {

    /**
     * 执行一次模型调用。
     *
     * @param request 模型请求
     * @param context 调用上下文
     * @return 模型结果
     */
    ModelResponse complete(ModelRequest request, ModelCallContext context);

    /**
     * 执行一次流式模型调用：逐段回调 {@code handler}，阻塞到流结束返回聚合结果。
     *
     * <p>默认网关实现路由到 {@link ModelProvider#stream}；自定义网关不重写时退化为
     * {@link #complete} 并把完整内容作为单次 token 回调——仅损失 token 粒度、不损失聚合语义。</p>
     *
     * @param request 模型请求
     * @param context 调用上下文
     * @param handler token 回调，可为 null
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
