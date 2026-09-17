/**
 * Agent 框架（单模块一体）：Agent / Workflow / Stage / Tool 契约与执行引擎同仓同模块，
 * 对外零业务依赖。
 *
 * <p>包按<b>概念</b>分组，契约与实现同包共存（不再有 core / engine 模块或包的分裂）：
 * {@code agent}（Agent 与执行入口）、{@code capability}（能力位模型与开关 SPI）、{@code workflow}（流程声明与驱动）、
 * {@code node}（节点形态与执行器）、{@code tool}（工具契约与注册/控制面）、{@code model}（模型端口）、
 * {@code prompt}、{@code result}、{@code plan}、{@code route}、{@code trace}、{@code guard}、
 * {@code session}、{@code cache}。</p>
 *
 * <p>命名规约：<b>接口 = 角色</b>（{@code AgentInvoker} / {@code WorkflowDriver} / {@code NodeExecutor} /
 * {@code RouteStrategy} / {@code TraceSink} …）；<b>实现 = 组件 + 角色</b>或 {@code Default*} / {@code InMemory*}
 * 前缀（{@code WorkflowEngineAgentInvoker} / {@code DefaultWorkflowDriver} / {@code InMemoryWorkflowCatalog} …）。
 * 一个组件扮演 SPI 角色时，要么在类名中写明角色，要么抽独立适配器——不允许"隐形 implement"。</p>
 *
 * <p>边界：框架不认识具体业务（不得 import {@code com.jjx.customer.platform} 下业务模块）；
 * 开放"周边"（节点形态 / 拦截器 / 路由策略 / 元数据 / 后端 SPI），不开放"内核"（横切语义与执行顺序）。</p>
 */
package com.jjx.customer.platform.agent.framework;
