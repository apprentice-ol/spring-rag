package com.agentframework.infra.modelgateway;

import java.util.List;
import java.util.function.Function;

/**
 * 回声模型提供方：不访问网络，按固定规则生成回答。
 *
 * <p>用于本地开发、示例与测试，保证执行链在没有真实模型时也能跑通。</p>
 */
public final class EchoModelProvider implements ModelProvider {

    private final String id;
    private final Function<ModelRequest, String> responder;

    /**
     * @param id        提供方标识
     * @param responder 回答生成函数，null 表示使用默认回声规则
     */
    public EchoModelProvider(String id, Function<ModelRequest, String> responder) {
        this.id = id == null || id.isBlank() ? "echo" : id;
        this.responder = responder == null ? request -> "回声：" + request.lastUserMessage() : responder;
    }

    /** 使用默认标识与回声规则构造。 */
    public EchoModelProvider() {
        this(null, null);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<String> models() {
        return List.of("echo", "default");
    }

    @Override
    public ModelResponse complete(ModelRequest request, ModelCallContext context) {
        String content = responder.apply(request);
        return new ModelResponse(id, request.model(), content, null,
                Usage.of(Usage.estimate(request.flatten()), Usage.estimate(content)), "stop", null);
    }
}
