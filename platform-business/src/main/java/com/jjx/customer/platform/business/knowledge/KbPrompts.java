package com.jjx.customer.platform.business.knowledge;

import java.util.List;

/**
 * knowledge 线的 Prompt 资产（react_loop 轴的 think 模板）。
 *
 * <p>工具协议与 ops 线的 Act 执行器一致：单行 JSON 意图
 * （{@code {"tool":...,"args":{...}}} / {@code {"answer":"..."}}）。
 * 正文本体在 classpath {@code prompts/agent/react-loop.md}（可被 DB 绑定覆盖）；
 * 缺失时组合器快速失败——正文只有文件/绑定一处来源，不再保留代码副本。</p>
 */
public final class KbPrompts {

    private KbPrompts() {
    }

    /** react think 的 prompt 资产 id（LLM 节点 promptRef，也是 classpath/DB 的 key）。 */
    public static final String REACT_THINK_ASSET = "agent/react-loop";

    /**
     * 组合 react think 完整模板（正文 + 工具协议块）。
     *
     * @param body      模板正文（classpath 资产或 DB 绑定提供；blank = 资产缺失，快速失败）
     * @param toolLines 工具协议块描述行（schema 渲染）
     * @return 完整模板
     */
    public static String composeReactThink(String body, List<String> toolLines) {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Prompt 资产缺失: " + REACT_THINK_ASSET
                    + "（classpath prompts/agent/react-loop.md 或 DB 绑定包必须提供）");
        }
        StringBuilder sb = new StringBuilder(body.strip());
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
