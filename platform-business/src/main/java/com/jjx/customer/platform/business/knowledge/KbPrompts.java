package com.jjx.customer.platform.business.knowledge;

import java.util.List;

/**
 * knowledge 线的 Prompt 资产（react_loop 轴的 think 模板）。
 *
 * <p>工具协议与 ops 线的 Act 执行器一致：单行 JSON 意图
 * （{@code {"tool":...,"args":{...}}} / {@code {"answer":"..."}}）。
 * 正文可被 classpath {@code prompts/agent/react-loop.md} 及 DB 绑定覆盖
 * （装配时优先取资产，缺失回退本类内置基线——代码即基线的双保险）。</p>
 */
public final class KbPrompts {

    private KbPrompts() {
    }

    /** react think 的 prompt 资产 id（LLM 节点 promptRef，也是 classpath/DB 的 key）。 */
    public static final String REACT_THINK_ASSET = "agent/react-loop";

    /** react think 模板正文基线（{{slots.question}} 与 {{slots.react_scratchpad}} 由渲染器替换）。 */
    public static final String REACT_THINK_BODY = """
            你是知识检索专家。任务：围绕用户问题自主决定查什么、何时停。

            ## 用户问题
            {{slots.question}}

            ## 已收集的过程记录
            {{slots.react_scratchpad}}

            ## 工作方法
            1. 先分析问题需要什么资料，构造精准的检索词（接口名、错误码、操作名）
            2. 检索命中的资料已带 [ref=N] 编号，判断是否足以回答
            3. 资料不足就换关键词再查；足够就给出 answer 收尾（answer 里可用 [ref=N] 引用来源）
            """;

    /**
     * 组合 react think 完整模板（正文 + 工具协议块）。
     *
     * @param body      模板正文（资产优先，缺失回退 {@link #REACT_THINK_BODY}）
     * @param toolLines 工具协议块描述行（schema 渲染）
     * @return 完整模板
     */
    public static String composeReactThink(String body, List<String> toolLines) {
        String text = body == null || body.isBlank() ? REACT_THINK_BODY : body;
        StringBuilder sb = new StringBuilder(text.strip());
        sb.append("\n\n## 可用工具（每轮只能输出一行 JSON，不要输出任何其他文本）\n");
        for (String line : toolLines) {
            sb.append("- ").append(line).append('\n');
        }
        sb.append("""

                ## 输出协议（严格单行 JSON）
                调用工具：{"tool":"工具名","args":{"参数":"值"}}
                给出答案：{"answer":"面向用户的完整回答"}
                """);
        return sb.toString();
    }
}
