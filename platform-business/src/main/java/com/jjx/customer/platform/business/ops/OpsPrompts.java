package com.jjx.customer.platform.business.ops;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;

import java.util.List;

/**
 * 诊断链路的 Prompt 拼装工具（协议块 / 插值渲染）。
 *
 * <p><b>2026-09-19 prompt 外置</b>：阶段正文（investigate/resolve/verify）、replan 裁决、
 * 抽槽、auto-resolve 骨架全部迁到 {@code prompts/workflow/ops_diagnose_v2/*.md}
 * （PromptStore classpath 基线 + 统一 prompt 管理入库），本类只保留<b>动态拼装</b>：
 * think 模板的工具协议块（{@link #compose}）与 auto-resolve 的槽位插值（{@link #renderAutoResolve}）。</p>
 *
 * <p>think 节点与模型之间的工具协议走 JSON 文本（参考实现 R1 的 JsonProtocolModelPort
 * 决策），由 {@code ops-act-*} 执行器解析——内核的 LLM 节点原样保留（AROUND_LLM
 * 拦截链、prompt 资产、消息组装），工具执行收敛在 Act 执行器里经框架 ToolExecutor 管道。</p>
 */
public final class OpsPrompts {

    /** auto-resolve 骨架模板的 key（{{now}}/{{missing}}/{{confirmed}}/{{question}} 四个插值位）。 */
    public static final String AUTO_RESOLVE_ASSET = "workflow/ops_diagnose_v2/auto-resolve";

    private OpsPrompts() {
    }

    /**
     * 渲染 auto-resolve 上下文补全的 user prompt（问用户之前的自主补全层）：
     * 由已有信息推断缺失槽位，高置信才输出，全部落 provenance 透明可纠正。
     *
     * @param template  骨架模板（PromptStore 读 {@link #AUTO_RESOLVE_ASSET}，含四个插值位）
     * @param missing   缺失槽位（名 → 取值说明）
     * @param confirmed 已有槽位值
     * @param question  用户原始输入
     * @param nowText   当前时间锚点（如 {@code 2026-09-17T15:04（周四）}）——口语时间
     *                  （「昨天下午」「下班前」「晚饭后」）换算的基准，没有它模型无从算绝对窗口
     * @return 渲染后的 user prompt
     */
    public static String renderAutoResolve(String template, List<String[]> missing,
            java.util.Map<String, String> confirmed, String question, String nowText) {
        StringBuilder missingLines = new StringBuilder();
        for (String[] slot : missing) {
            missingLines.append("- ").append(slot[0]).append("：").append(slot[1]).append('\n');
        }
        StringBuilder confirmedLines = new StringBuilder();
        confirmed.forEach((name, value) -> confirmedLines.append(name).append(" = ").append(value).append('\n'));
        return template
                .replace("{{now}}", nowText == null || nowText.isBlank() ? "未知" : nowText)
                .replace("{{missing}}", missingLines.toString())
                .replace("{{confirmed}}", confirmedLines.toString())
                .replace("{{question}}", question == null ? "" : question);
    }

    /**
     * 组装完整阶段模板：正文 + 工具协议块（含白名单与 scratchpad 回灌位）+ replan 修正段。
     *
     * @param body          阶段正文（PromptStore 按 stage.promptAssetId() 读取）
     * @param tools         白名单工具的「id(参数)：说明」列表（**必须含参数名**，
     *                      否则模型只能猜参数——实测会猜出 start_time/env 这类不存在的名字）
     * @param scratchpadSlot 过程记录槽位名
     * @return 完整模板
     */
    public static String compose(String body, List<String> tools, String scratchpadSlot) {
        StringBuilder sb = new StringBuilder(body);
        if (tools == null || tools.isEmpty()) {
            sb.append("\n（本阶段无可用工具，直接给 answer）\n");
        } else {
            sb.append("""

                    ## 工具使用协议
                    需要调用工具时，只输出一行 JSON：{"tool":"工具名","args":{...}}
                    需要向用户补充信息时，只输出一行 JSON：{"ask_user":"要问的问题","slots":["希望用户补充的槽位名",...]}
                    判断继续查也无法推进（缺用户才知道的信息）时，只输出一行 JSON：{"escalate":"原因"}
                    可以直接给出结论时，只输出一行 JSON：{"answer":"最终结论"}
                    不要输出 markdown 围栏、不要输出多余解释。

                    ## ask_user 的 slots 可选值（只能取下列名字，没有合适槽位时省略 slots 字段）
                    """);
            for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
                sb.append("- ").append(spec.name()).append("：").append(spec.question()).append('\n');
            }
            sb.append("""

                    目录槽覆盖不了的信息维度（如发版版本号、上游依赖系统、具体业务单号类型），
                    可以在 slots 里用对象声明**自定义槽位**（只在确实需要用户补充时声明，问句要具体）：
                    {"ask_user":"问句","slots":[{"name":"release_version","question":"最近一次发版的版本号？","hint":"如 v2.3.1"}]}
                    自定义槽位名不得与上面的目录槽重名；用户回复会沉淀为该槽位值，后续阶段直接可用。

                    ## 本阶段可用工具（白名单之外的工具一律不可用）
                    **args 的键必须严格使用下面列出的参数名，不得自造参数名。**
                    """);
            for (String tool : tools) {
                sb.append("- ").append(tool).append('\n');
            }
            sb.append("\n## 已执行的工具结果（Action/Observation 过程记录，不要重复执行）\n")
                    .append("{{slots.").append(scratchpadSlot).append("}}\n");
        }
        // 人在环中：用户补充说明 / 方向指令（软消费——模型自行判断是否采纳，不改图结构）。
        // 多数轮次没有这一项，用模板默认值渲染成「（无）」，避免空标题诱发模型自行脑补
        sb.append("\n## 用户补充说明（用户在排查过程中给出的信息或方向，按需采纳）\n")
                .append("{{slots.").append(ActExecutor.USER_DIRECTIVE_SLOT).append("|（无）}}\n");
        // replan 修正段：未裁决时渲染为空（replan_note 默认空串），adjust 时携带重跑提示
        sb.append("\n## 修正要求（上一轮 replan 裁决）\n{{slots.replan_note}}\n");
        return sb.toString();
    }

    /**
     * 渲染工具的「id(参数)：说明」一行（协议块的可用工具清单）。
     *
     * @param schema 工具契约
     * @return 形如 {@code query_logs(trace_id?, start?, end?, keyword?, level?, limit?)：查询服务日志排障。…}
     */
    public static String describeTool(com.agentframework.definition.tool.ToolSchema schema) {
        StringBuilder params = new StringBuilder();
        if (schema != null && schema.parameters() != null) {
            for (com.agentframework.definition.tool.ToolParameter parameter : schema.parameters()) {
                if (params.length() > 0) {
                    params.append(", ");
                }
                params.append(parameter.name());
                if (!parameter.required()) {
                    params.append('?');
                }
            }
        }
        return schema == null ? "" : schema.name() + "(" + params + ")：" + schema.description();
    }
}
