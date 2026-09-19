package com.jjx.customer.platform.business.ops.workflow.stages;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;

import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.jjx.customer.platform.business.ops.HumanResponseInterpreter;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.tool.ValidateRequestTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 阶段模块装配期的共享运行环境（替代原先执行器逐个手工传参的依赖穿透）。
 *
 * @param sharedRegistry 共享工具注册表（RAG 与诊断同一张表）
 * @param toolExecutor   工具执行管道（Act 执行器与反查共用）
 * @param mapper         JSON 解析
 * @param model          轻量问答模型（抽槽 / 自主补全推断 / replan 裁决同源；null = 确定性降级）
 * @param clock          时钟（自主补全的时间换算，测试可注入固定值）
 * @param maxLlmCalls    全链路 LLM 预算上限
 * @param schemaText     工具 id → Prompt 协议块描述文本（describeTool 渲染）
 * @param validateTool   报文校验工具（出口护栏 resolver 的数据源）
 * @param promptBody     prompt key → 正文文本（绑定包覆盖优先，未登记/缺失回退 null）；
 *                       think 模板的正文来源（正文可被 DB 资产管理）
 * @param promptRegister 装配期组合模板注册（assetId → 正文+协议块组合产物，优先级最高的运行真相）
 */
public record SharedDeps(DefaultToolRegistry sharedRegistry, DefaultToolExecutor toolExecutor,
                         ObjectMapper mapper, SingleTurnModel model, Clock clock,
                         int maxLlmCalls, Map<String, String> schemaText, ValidateRequestTool validateTool,
                         Function<String, String> promptBody, BiConsumer<String, String> promptRegister) {

    /** 兼容旧构造（无 prompt 管道，prompt 走内置常量注册）。 */
    public SharedDeps(DefaultToolRegistry sharedRegistry, DefaultToolExecutor toolExecutor,
                      ObjectMapper mapper, SingleTurnModel model, Clock clock,
                      int maxLlmCalls, Map<String, String> schemaText, ValidateRequestTool validateTool) {
        this(sharedRegistry, toolExecutor, mapper, model, clock, maxLlmCalls, schemaText, validateTool,
                key -> null, (key, template) -> {
                });
    }

    /**
     * 用户回复解释器（人在环中 P2）：解释器是纯函数式的（模型 + JSON + prompt 源），
     * 各执行器各持一份即可，无需共享实例。
     *
     * @return 回复解释器（模型不可用时内部退化到 {@code #decision:} 前缀解析）
     */
    public HumanResponseInterpreter humanResponseInterpreter() {
        return new HumanResponseInterpreter(model, mapper, promptBody);
    }
}
