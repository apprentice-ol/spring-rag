package com.jjx.customer.platform.routing;


import java.util.Optional;

/**
 * 路由规则（可插拔，@Component 即注册）：只裁决"选哪个执行者"——
 * 会话恢复前置处理、悬空指代澄清、闲聊短路是编排层主干行为，不进规则表。
 *
 * <p>规则表按 {@link #priority()} 升序求值，首个命中即胜出。规则保持代码注册，
 * 不做 yaml/DSL（路由是行为不是配置，类型安全与可测性优先）。
 *
 * <p>新增能力域 = RagParadigm 枚举 + Agent 实现 + 本接口规则（含域描述）+ 前端选项，
 * pipeline/引擎零改。
 */
public interface RouteRule {

    /** 优先级（数值小者优先）。正则短路类给个位数，意图域类给十位数，兜底类百位。 */
    int priority();

    /** 求值：命中返回目标；不命中 empty。规则自行判断输入可用性（如意图域规则在 intent==null 时不命中）。 */
    Optional<RouteDecision> match(RouteContext ctx);

    /**
     * 意图域描述（动态拼进意图分类 prompt 的域清单段，让新 workflow 的域自动进入分类器视野）。
     * 返回 null = 非意图域规则（如正则短路）或域描述已由同域其他规则提供。
     * 格式：{@code ops_diagnose：判定说明（什么问题该选此域，举例）}。
     */
    default String domainDescriptor() {
        return null;
    }
}
