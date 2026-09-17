package com.jjx.customer.platform.agent.framework.model;

/**
 * 模型调用端口（业务侧适配 Spring AI / 其他 SDK）。
 *
 * <p>框架只依赖本端口，不依赖任何具体模型客户端——测试可注入假实现。</p>
 */
public interface ModelPort {

    ModelReply complete(ModelRequest request);
}
