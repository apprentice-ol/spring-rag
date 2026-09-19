package com.agentframework.infra.modelgateway;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 脚本化模型提供方：按预设序列返回结果，并记录全部请求。
 *
 * <p>用于测试多轮执行链；脚本耗尽后可回退到自定义回答函数。</p>
 */
public final class ScriptedModelProvider implements ModelProvider {

    private final String id;
    private final Deque<ModelResponse> scripted = new ArrayDeque<>();
    private final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
    private Function<ModelRequest, ModelResponse> fallback;

    /** @param id 提供方标识 */
    public ScriptedModelProvider(String id) {
        this.id = id == null || id.isBlank() ? "scripted" : id;
    }

    /** 使用默认标识构造。 */
    public ScriptedModelProvider() {
        this(null);
    }

    /**
     * 依次入队脚本结果。
     *
     * @param responses 预设结果
     * @return 当前实例
     */
    public ScriptedModelProvider enqueue(ModelResponse... responses) {
        for (ModelResponse response : responses) {
            scripted.addLast(response);
        }
        return this;
    }

    /**
     * 依次入队文本结果。
     *
     * @param contents 预设文本
     * @return 当前实例
     */
    public ScriptedModelProvider enqueueText(String... contents) {
        for (String content : contents) {
            scripted.addLast(ModelResponse.text(content));
        }
        return this;
    }

    /**
     * 设置脚本耗尽后的兜底逻辑。
     *
     * @param fallback 回答生成函数
     * @return 当前实例
     */
    public ScriptedModelProvider fallback(Function<ModelRequest, ModelResponse> fallback) {
        this.fallback = fallback;
        return this;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ModelResponse complete(ModelRequest request, ModelCallContext context) {
        requests.add(request);
        ModelResponse response = scripted.pollFirst();
        if (response != null) {
            return response.withIdentity(id, request.model());
        }
        if (fallback != null) {
            return fallback.apply(request).withIdentity(id, request.model());
        }
        return ModelResponse.text("脚本已耗尽：" + request.lastUserMessage()).withIdentity(id, request.model());
    }

    /** @return 收到的全部请求 */
    public List<ModelRequest> requests() {
        return new ArrayList<>(requests);
    }

    /** @return 剩余脚本条数 */
    public int remaining() {
        return scripted.size();
    }

    /** 清空请求记录与脚本。 */
    public void reset() {
        requests.clear();
        scripted.clear();
    }
}
