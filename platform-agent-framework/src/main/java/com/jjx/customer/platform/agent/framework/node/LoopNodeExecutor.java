package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.tool.AgentTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.tool.ToolControl;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ModelRequest;
import com.jjx.customer.platform.agent.framework.model.ModelTurn;
import com.jjx.customer.platform.agent.framework.model.ToolCall;
import com.jjx.customer.platform.agent.framework.model.ToolObservation;
import com.jjx.customer.platform.agent.framework.model.ToolSchema;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具循环节点：模型在"扩展工具白名单 + 引擎注入的 BaseTool"范围内自主决策，多步循环。
 *
 * <p>出环：模型给出最终文本（无工具调用）；到顶步数则按 MAX_STEPS 出环（产出最后一次文本，可为空）。</p>
 */
public class LoopNodeExecutor implements NodeExecutor {

    private final ModelPort modelPort;
    private final ToolRegistry toolRegistry;

    public LoopNodeExecutor(ModelPort modelPort, ToolRegistry toolRegistry) {
        this.modelPort = modelPort;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public NodeKind nodeKind() {
        return NodeKind.LOOP;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        String system = context.promptOrNull(context.stage().systemPromptKey());
        if (system == null) {
            throw new IllegalStateException("阶段缺 prompt key: " + context.stage().systemPromptKey()
                    + "（agent=" + context.plan().agent().id() + "）");
        }
        if (context.systemAppend() != null && !context.systemAppend().isBlank()) {
            system = system + "\n\n## 修正要求（上一轮 replan 裁决）\n" + context.systemAppend();
        }
        List<ToolSchema> schemas = schemas(context);
        List<String> observations = new ArrayList<>();
        List<ModelTurn> turns = new ArrayList<>();
        List<ContextArtifact> artifacts = new ArrayList<>();
        int llmCalls = 0;
        int toolCalls = 0;
        String lastText = null;

        int maxSteps = Math.max(1, context.stage().maxSteps());
        BudgetView budget = context.budget();
        for (int step = 0; step < maxSteps; step++) {
            if (budget.exhausted()) {
                return new NodeResult(lastText, "BUDGET_EXHAUSTED", llmCalls, toolCalls, artifacts);
            }
            ModelReply reply = modelPort.complete(new ModelRequest(
                    system, context.request().input(), schemas, observations, turns));
            llmCalls++;
            budget = budget.consumeLlmCall();
            if (!reply.hasToolCalls()) {
                lastText = reply.text();
                return NodeResult.ok(lastText, llmCalls, toolCalls, artifacts);
            }
            turns.add(ModelTurn
                    .assistantToolCalls(reply.toolCalls()));
            List<ToolObservation> stepResults =
                    new ArrayList<>();
            for (ToolCall call : reply.toolCalls()) {
                ToolResult result = toolRegistry.invoke(ToolInvocation.of(call, context));
                toolCalls++;
                observations.add("[" + call.toolName() + "] " + result.asObservation());
                stepResults.add(new ToolObservation(
                        "call_" + step + "_" + toolCalls, call.toolName(), result.asObservation()));
                artifacts.addAll(result.artifacts());
                if (result.control() == ToolControl.FINISH) {
                    return NodeResult.ok(result.content(), llmCalls, toolCalls, artifacts);
                }
                if (result.control() == ToolControl.ASK_USER) {
                    return new NodeResult(result.content(), "ASK_USER", llmCalls, toolCalls, artifacts);
                }
                if (result.control() == ToolControl.ESCALATE) {
                    return new NodeResult(result.content(), "ESCALATE", llmCalls, toolCalls, artifacts);
                }
            }
            turns.add(ModelTurn.toolResults(stepResults));
        }
        return new NodeResult(lastText, "MAX_STEPS", llmCalls, toolCalls, artifacts);
    }

    /** 白名单扩展工具 + 引擎注入的 BaseTool（控制面永远可用；同名去重）。
     *  白名单只允许 ExtensionTool——写 BaseTool 名 = 装配错误（不静默忽略）。 */
    private List<ToolSchema> schemas(NodeContext context) {
        Map<String, ToolSchema> schemas = new LinkedHashMap<>();
        for (String name : context.stage().extensionTools()) {
            AgentTool tool = toolRegistry.byName(name).orElseThrow(
                    () -> new IllegalStateException("阶段白名单里的工具未注册: " + name));
            if (tool.kind() != com.jjx.customer.platform.agent.framework.tool.ToolKind.EXTENSION) {
                throw new IllegalStateException("阶段白名单只允许 ExtensionTool（BaseTool 由引擎注入）: "
                        + name + "（stage=" + context.stage().name() + "）");
            }
            schemas.putIfAbsent(tool.name(),
                    tool.toSchema());
        }
        for (AgentTool base : toolRegistry.baseTools()) {
            schemas.putIfAbsent(base.name(), base.toSchema());
        }
        return List.copyOf(schemas.values());
    }
}
