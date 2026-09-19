package com.jjx.customer.platform.business.ops;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;

import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;

import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.StartOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.engine.outcome.RunOutcomeMapper;
import com.jjx.customer.platform.business.ops.workflow.stages.SharedDeps;
import com.jjx.customer.platform.business.ops.workflow.stages.StageModule;
import com.jjx.customer.platform.business.ops.tool.CurrentTimeTool;
import com.jjx.customer.platform.business.ops.tool.ValidateRequestTool;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ops 诊断图挂起恢复端到端（内存引擎 + 双通道脚本模型，替代旧 OpsFrameworkPathTest）：
 * 缺必填槽位 → 自主补全无可推断 → 问齐挂起（CLARIFY，O1）；补答恢复 → 不重问 →
 * 三阶段 think（answer 协议）+ replan（continue）→ conclude 直答（DIRECT，O2/O6）。
 */
class OpsGraphSuspendTest {

    /** 边角调用（抽槽 / 自主补全 / replan 裁决）的脚本模型：按调用顺序出队。 */
    private static final class ScriptedAskModel implements SingleTurnModel {
        final ArrayDeque<String> replies = new ArrayDeque<>();

        ScriptedAskModel enqueue(String... values) {
            for (String v : values) {
                replies.add(v);
            }
            return this;
        }

        @Override
        public String ask(String system, String user) {
            return replies.isEmpty() ? "{\"action\":\"continue\"}" : replies.poll();
        }
    }

    private static Engine engineOf(ScriptedAskModel askModel, ScriptedModelProvider thinkModel) {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        ValidateRequestTool validateTool = new ValidateRequestTool(new ObjectMapper());
        registry.register(validateTool);
        registry.register(new CurrentTimeTool());
        // 图声明的工具白名单要求全部可解析（AgentValidator 启动校验）：注册无副作用的替身
        com.agentframework.engine.toolexecutor.Tool stubLogs = new com.agentframework.engine.toolexecutor.Tool() {
            @Override
            public String id() {
                return "query_logs";
            }

            @Override
            public com.agentframework.definition.tool.ToolSchema schema() {
                return com.agentframework.definition.tool.ToolSchema.noArgs(id(), "查日志（测试替身）");
            }

            @Override
            public com.agentframework.engine.toolexecutor.ToolResult invoke(
                    com.agentframework.engine.toolexecutor.ToolInput input,
                    com.agentframework.engine.toolexecutor.ToolContext context) {
                return com.agentframework.engine.toolexecutor.ToolResult.ok("（测试替身）无日志");
            }
        };
        com.agentframework.engine.toolexecutor.Tool stubRetrieval =
                new com.agentframework.engine.toolexecutor.Tool() {
                    @Override
                    public String id() {
                        return "retrieve_knowledge";
                    }

                    @Override
                    public com.agentframework.definition.tool.ToolSchema schema() {
                        return com.agentframework.definition.tool.ToolSchema.noArgs(id(), "检索（测试替身）");
                    }

                    @Override
                    public com.agentframework.engine.toolexecutor.ToolResult invoke(
                            com.agentframework.engine.toolexecutor.ToolInput input,
                            com.agentframework.engine.toolexecutor.ToolContext context) {
                        return com.agentframework.engine.toolexecutor.ToolResult.ok("（测试替身）无资料");
                    }
                };
        registry.register(stubLogs);
        registry.register(stubRetrieval);
        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(registry, null, null, null);

        EngineBuilder builder = EngineBuilder.create()
                .toolRegistry(registry)
                .modelProvider(thinkModel);
        // think 模板组合产物必须可被 PromptProvider 解析（AgentValidator 校验 promptRef）
        java.util.function.BiConsumer<String, String> promptRegister =
                (key, template) -> builder.prompt(
                        com.agentframework.definition.prompt.PromptDefinition.template(key, template));

        // 协议块文本（wireRuntime 组合 think 模板用；测试不依赖 schema 真实渲染）
        Map<String, String> schemaText = Map.of(
                "query_logs", "query_logs(traceId?, keyword?, start?, end?, limit?)：查日志",
                "validate_request", "validate_request(iface, payload)：按接口规范校验报文",
                "get_time", "get_time()：当前时间",
                "retrieve_knowledge", "retrieve_knowledge(query, topN?)：检索知识库");
        SharedDeps deps = new SharedDeps(registry, toolExecutor, new ObjectMapper(), askModel,
                Clock.systemDefaultZone(), 24, schemaText, validateTool,
                new com.jjx.customer.platform.config.prompt.PromptStore()::raw, promptRegister);

        Map<String, StageModule> modules = OpsDiagnoseWorkflowFactory.modules();
        com.agentframework.definition.workflow.WorkflowDefinition workflow = OpsDiagnoseWorkflowFactory.create();
        OpsDiagnoseWorkflowFactory.reconcile(workflow, modules.values());
        modules.values().forEach(module -> module.wireRuntime(builder, deps));
        builder.workflow(workflow)
                .agent(AgentDefinition.builder("ops_diagnose").workflow("ops_diagnose_v2").build());
        return builder.build();
    }

    @Test
    void 槽位给全也先查日志再进确认门() {
        // 用户把必填全给了（环境/接口/时间 + trace_id）：仍要先跑自主补全（日志反查是一手信息源，
        // 不是"缺槽才自救"的兜底），再进确认门——这条链上不能再有"缺必填才补全"的前置门
        ScriptedAskModel askModel = new ScriptedAskModel();        // 队列空：边角调用走模型默认返回
        ScriptedModelProvider thinkModel = new ScriptedModelProvider();
        Engine engine = engineOf(askModel, thinkModel);

        Session session = engine.startSession(engine.loadAgent("ops_diagnose", "latest"),
                StartOptions.defaults().withSessionId("ops-test-2"));
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("environment", "prod");
        slots.put("interface", "/api/invoice/reverse");
        slots.put("time", "最近1小时");
        slots.put("trace_id", "abc1234567890abcdef1234567890ab");
        RunResult result = engine.run(session, new Input("帮我看看为什么报错", Map.of(), slots));

        assertTrue(result.visitedNodes().contains("auto_resolve"),
                "日志反查是固定一步：槽位齐备也要先查一遍，实际 visited=" + result.visitedNodes());
        assertEquals("confirm_slots", result.suspendedNode(), "查完直接进确认门（不经过问齐）");
    }

    @Test
    void 缺槽挂起_补答恢复_直答收尾() {
        // 边角脚本：抽槽(空) → 自主补全(无推断)
        // | 恢复后：回复解释(P2，抽到 symptoms 故抽槽器不再重复调) → replan×2 = continue
        ScriptedAskModel askModel = new ScriptedAskModel()
                .enqueue("{\"environment\":null,\"interface\":null,\"time\":null}", "[]",
                        "{\"slots\":{\"symptoms\":\"偶发\"},\"directive\":\"报错只在灰度机器上出现\"}",
                        "{\"action\":\"continue\"}", "{\"action\":\"continue\"}");
        // think 脚本：恢复后三阶段各一次 answer 收尾
        //（res 阶段有出口护栏：answer 含 JSON 块会被 schema 校验打回——测试脚本刻意不带 JSON 块）
        ScriptedModelProvider thinkModel = new ScriptedModelProvider()
                .enqueueText("{\"answer\":\"定位：订单服务 NPE，位于 createInvoice\"}")
                .enqueueText("{\"answer\":\"已检索接口文档，正确报文已生成且字段核对一致\"}")
                .enqueueText("{\"answer\":\"结论：字段缺失导致，已给修正报文并校验通过\"}");
        Engine engine = engineOf(askModel, thinkModel);

        // ---- 首轮：缺必填三槽 → CLARIFY ----
        Session session = engine.startSession(engine.loadAgent("ops_diagnose", "latest"),
                StartOptions.defaults().withSessionId("ops-test-1"));
        RunResult first = engine.run(session,
                new Input("接口报错了帮忙看看", Map.of(), Map.of()));

        assertTrue(first.suspended(), "缺必填槽位应挂起");
        assertEquals(OutcomeKind.CLARIFY, RunOutcomeMapper.kindOf(first));
        assertTrue(first.output().contains("环境") || first.output().contains("接口"), "问齐文案应指向缺失槽位");

        // ---- 恢复：补齐三槽（Input.slots 直写，跳过抽槽）→ **停在确认门**（先确认再诊断）----
        Map<String, Object> resumeSlots = new LinkedHashMap<>();
        resumeSlots.put("user_clarify", "prod 环境 /api/invoice/reverse 最近1小时");
        resumeSlots.put("environment", "prod");
        resumeSlots.put("interface", "/api/invoice/reverse");
        resumeSlots.put("time", "最近1小时");
        RunResult second = engine.resume(session,
                new Input("prod 环境 /api/invoice/reverse 最近1小时", Map.of(), resumeSlots));

        assertTrue(second.suspended(), "槽位齐备后应先出确认单，而不是直接开诊断");
        assertEquals("confirm_slots", second.suspendedNode(), "确认门节点");
        String confirmCard = String.valueOf(second.slots().get(HumanRequest.PENDING_SLOT));
        assertTrue(confirmCard.contains("\"kind\":\"CONFIRM\""), "确认单是一张 CONFIRM 请求：" + confirmCard);
        assertTrue(confirmCard.contains("/api/invoice/reverse"), "确认单要摊开已收集的基本信息");

        // ---- 确认：开始诊断 → 三阶段 → conclude 直答 ----
        Map<String, Object> confirmSlots = new LinkedHashMap<>();
        confirmSlots.put("user_clarify", "#decision:confirm");
        RunResult third = engine.resume(session, new Input("#decision:confirm", Map.of(), confirmSlots));

        assertTrue(third.successful(), "确认后应走完全程：error=" + third.error());
        assertEquals(OutcomeKind.DIRECT, RunOutcomeMapper.kindOf(third));
        // P2 语义通道：恢复时的回复解释产物要真的落进会话槽位（软消费通道跨阶段可见）
        assertEquals("偶发", third.slots().get("symptoms"), "解释器抽到的槽值应落槽");
        assertEquals("报错只在灰度机器上出现", third.slots().get("user_directive"),
                "方向指令应落 user_directive（think prompt 可见）");
        assertTrue(third.visitedNodes().contains("confirm_slots"), "确认门应在轨迹里");
        assertTrue(third.output().contains("结论"),
                "conclude 应直出最终结论，实际 output=" + third.output()
                        + "，visited=" + third.visitedNodes()
                        + "，ver_stage_output=" + third.slots().get("ver_stage_output"));
    }
}
