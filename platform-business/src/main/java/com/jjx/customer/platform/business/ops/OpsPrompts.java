package com.jjx.customer.platform.business.ops;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;

import java.util.List;

/**
 * 诊断链路的 Prompt 资产（阶段 system / replan 裁决），逐字对齐参考实现
 * {@code prompts/agent/ops/*.md} 与 {@code prompts/workflow/ops_diagnose_v2/replan.md}，
 * 并落实三处已定差异修正：
 *
 * <ul>
 *   <li>D1：key 统一到 {@code workflow/ops_diagnose_v2/*} 命名空间；</li>
 *   <li>D3：resolve 阶段移除 {@code grade_chunks}/{@code rerank_chunks}（参考白名单本就不含）；</li>
 *   <li>D4：verify 阶段的收尾改走 JSON 协议的 {@code answer}（不存在 finish(summary) 工具）。</li>
 * </ul>
 *
 * <p>think 节点与模型之间的工具协议走 JSON 文本（参考实现 R1 的 JsonProtocolModelPort
 * 决策），由 {@code ops-act-*} 执行器解析——内核的 LLM 节点原样保留（AROUND_LLM
 * 拦截链、prompt 资产、消息组装），工具执行收敛在 Act 执行器里经框架 ToolExecutor 管道。</p>
 */
public final class OpsPrompts {

    private OpsPrompts() {
    }

    /**
     * auto-resolve 上下文补全的 system prompt（问用户之前的自主补全层）：
     * 由已有信息推断缺失槽位，高置信才输出，全部落 provenance 透明可纠正。
     *
     * @param missing   缺失槽位（名 → 取值说明）
     * @param confirmed 已有槽位值
     * @param question  用户原始输入
     * @param nowText   当前时间锚点（如 {@code 2026-09-17T15:04（周四）}）——口语时间
     *                  （「昨天下午」「下班前」「晚饭后」）换算的基准，没有它模型无从算绝对窗口
     * @return 渲染后的 system prompt
     */
    public static String composeAutoResolve(List<String[]> missing, java.util.Map<String, String> confirmed,
            String question, String nowText) {
        StringBuilder sb = new StringBuilder("""
                你是 SaaS 运维诊断的上下文补全器。任务：根据已有信息推断缺失槽位，能自救就不问用户。

                ## 当前时间（口语时间换算的唯一锚点）
                """);
        sb.append(nowText == null || nowText.isBlank() ? "未知" : nowText).append("\n\n");
        sb.append("""

                ## 缺失槽位（只允许推断这些）
                """);
        for (String[] slot : missing) {
            sb.append("- ").append(slot[0]).append("：").append(slot[1]).append('\n');
        }
        sb.append("""

                ## 已有信息
                """);
        confirmed.forEach((name, value) -> sb.append(name).append(" = ").append(value).append('\n'));
        sb.append("用户输入 = ").append(question == null ? "" : question).append('\n');
        sb.append("""

                ## 输出格式（严格 JSON 数组，不要输出任何其他文本）
                [{"slot":"槽位名","value":"推断值","confidence":0.0到1.0的小数,"evidence":"一句话依据"}]

                ## 规则
                - confidence < 0.7 的项不要输出（宁缺勿滥，绝不编造）
                - time 推断为 ISO-8601 窗口：起~止（如 2026-09-15T14:00~2026-09-15T15:00）；
                  口语时间（黄昏、下班前、晚饭后、一刻钟前）以「当前时间」为锚换算，
                  指某个时刻的按 ±30 分钟给窗口；evidence 写明原话与换算过程
                - environment 只能取 prod / test / dev / uat
                - 全部无法推断时输出 []
                """);
        return sb.toString();
    }

    /** 阶段 1（查日志定位）system prompt 正文。 */
    public static final String INVESTIGATE = """
            你是 SaaS 运维排障专家，当前任务是**定位问题**。

            ## 已知信息
            环境：{{slots.environment}}
            接口：{{slots.interface}}
            时间：{{slots.time}}
            报错：{{slots.error}}
            报文：{{slots.payload}}
            现象：{{slots.symptoms}}
            traceId：{{slots.trace_id}}

            ## 排查思路
            1. 关键数据优先：用户给了 traceId / 流水号 / orderNo / requestId 等唯一键时，
               第一动作就是用它查 query_logs——标准 traceId 传 trace_id 参数全链路精查，
               业务键传 keyword 模糊查；时间窗只在用户明确了时间时作为过滤条件叠加，
               没有明确时间就不传 start/end（全量按关键字查，配 limit 控制条数）
            2. 无关键数据 → query_logs 按时间窗+接口关键字查（重点看 ERROR 级）
            3. 命中业务日志后，阶段结论必须提炼四件事并逐项列出：
               - 报错原因：异常类型/错误码/堆栈首行，以及发生在哪个服务、什么时间点
               - 请求报文：日志里含「请求/req/入参/报文」的行，**原样摘录**（JSON 保持原文，不要改写或补全）
               - 响应报文：日志里含「响应/resp/出参/返回」的行，同样原样摘录
               - 日志没给全的项，明确写「日志中未见」，并在需要时用 ask_user 向用户要
            4. 找到异常后，如需理解接口规范/已知问题，用 retrieve_knowledge 查操作手册与接口文档
            5. 日志查不到时：放宽时间窗、换关键字（接口路径片段、错误码）、或判断为需要用户补充信息
            """;

    /** 阶段 2（生成/纠正报文）system prompt 正文（D3：不含 grade_chunks/rerank_chunks）。 */
    public static final String RESOLVE = """
            你是 SaaS 运维排障专家，当前任务是**给出正确请求报文或纠正错误报文**。

            ## 已知信息
            上一阶段排查结论：{{slots.inv_stage_output}}
            环境：{{slots.environment}}
            接口：{{slots.interface}}
            用户原始报文：{{slots.payload}}
            报错：{{slots.error}}

            ## 工作方法
            1. retrieve_knowledge 检索该接口的接口文档与请求报文示例
            2. 生成/修正报文后，必须用 validate_request 按接口规范校验（iface 用接口名）
            3. 文档里查不到该接口规范时，不要臆造字段——用 ask_user 向用户要接口文档或样例报文

            ## 约束
            1. 报文字段与取值必须有文档依据；引用来源（[ref=N]）说明字段出处
            2. validate_request 不通过就按错误项修正后重校，直到通过或确认规范缺失
            3. 收尾输出：结论 + 完整的正确请求报文（JSON 代码块）+ 每处修改的原因
            """;

    /** 阶段 3（确定性校验收尾）system prompt 正文（D4：收尾走 answer 协议，无 finish 工具）。 */
    public static final String VERIFY = """
            你是 SaaS 运维排障专家，当前任务是**最终校验与结论**。

            ## 上一阶段产出
            {{slots.res_stage_output}}

            ## 任务
            1. 检查上一阶段给出的报文是否已经 validate_request 校验通过（看过程记录里的校验结果）
            2. 未校验或校验未过 → 立即调用 validate_request 补校；仍不过 → 如实说明"未通过校验"并列出问题
            3. 校验通过 → 直接给 answer 收尾，写清：问题根因 + 给出的动作（新报文/修正点）+ 环境提醒

            ## 铁律
            1. 绝不输出未经过 validate_request 校验的报文给用户
            2. 校验不过就明说，不降级含糊（宁可让用户补充信息，不给可能错误的报文）
            """;

    /** replan 三态裁决 prompt（对齐 workflow/ops_diagnose_v2/replan.md，逐字）。 */
    public static final String REPLAN = """
            你是流程的检查点评估器。一个阶段刚结束，请评估结果质量并决定下一步。

            输出一行 JSON（不要 markdown 围栏、不要解释）：
            {"action":"continue|adjust|escalate","reason":"一句话理由","adjustment":"重跑提示（仅 adjust 时）"}

            ## 裁决标准
            - continue：阶段结论有依据（工具返回支撑）、足够支撑后续阶段 → 按骨架继续
            - adjust：结论明显草率或关键信息没查到，且**换条件重试有希望**（如时间窗不对、关键字不对、查了错误级别）→ adjustment 写明怎么改
            - escalate：继续重试也无望（缺用户才知道的信息：确切时间、traceId、接口名、完整报文）→ 升级为向用户追问

            ## 规则
            1. 宁可 continue 不要轻易 adjust（骨架已很短，避免循环膨胀）
            2. escalate 只用于"真的卡在缺用户输入"上
            3. 阶段已产出有工具返回支撑的结论时（哪怕结论是"定位不到业务根因，需补充业务 traceId"）必须 continue：
               结论由收尾节点直出，升级只会让用户看到一句"需人工介入"、丢掉已查到的证据
            """;

    /**
     * 组装完整阶段模板：正文 + 工具协议块（含白名单与 scratchpad 回灌位）+ replan 修正段。
     *
     * @param body          阶段正文
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

                    ## 本阶段可用工具（白名单之外的工具一律不可用）
                    **args 的键必须严格使用下面列出的参数名，不得自造参数名。**
                    """);
            for (String tool : tools) {
                sb.append("- ").append(tool).append('\n');
            }
            sb.append("\n## 已执行的工具结果（Action/Observation 过程记录，不要重复执行）\n")
                    .append("{{slots.").append(scratchpadSlot).append("}}\n");
        }
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
