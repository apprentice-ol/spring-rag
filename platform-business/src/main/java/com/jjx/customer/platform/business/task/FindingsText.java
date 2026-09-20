package com.jjx.customer.platform.business.task;

import java.util.List;

/**
 * 主张（{@link AgentFinding}）的编号渲染：{@code - [#n] [kind] claim} 行序列。
 *
 * <p><b>为什么必须收敛成一份</b>：编号是用户回指「第几条」的锚点（{@code applyDenials} 按编号落位
 * RETRACTED，{@code TaskFollowUpQa} 按编号让模型引用），此前的行格式与常量在
 * {@code OpsRunner.renderFindings} 与 {@code TaskFollowUpQa.renderClaims} 两处逐字段复制、
 * 靠注释约定锁步——注释锁不住代码，锁步必须由同一个函数保证。</p>
 *
 * <p><b>顺序契约</b>：编号 = 入参列表下标 + 1，调用方必须传与 {@code activeOf} 查询同序的列表
 * （否定的编号会标错对象）。超出上限显式写明省略条数，绝不静默截断——模型引用不存在的编号
 * 比没有编号更坏。</p>
 */
public final class FindingsText {

    /** 注入主张的最大条数（有界是硬要求：组装成本与主张数量解耦）。 */
    public static final int MAX_ITEMS = 10;

    /** 单条主张正文的字符上限。 */
    public static final int CLAIM_MAX_CHARS = 400;

    private FindingsText() {
    }

    /**
     * 编号渲染（无标题段、空列表返回空串——标题与空态话术由调用方决定）。
     *
     * @param findings 生效主张（顺序即编号，须与 {@code activeOf} 查询一致）
     * @param maxItems 最大条数（超出部分显式省略）
     * @param claimMax 单条正文截断上限
     * @return 行序列文本；空列表返回 {@code ""}
     */
    public static String renderNumbered(List<AgentFinding> findings, int maxItems, int claimMax) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < findings.size(); i++) {
            if (i >= maxItems) {
                sb.append("- （其余 ").append(findings.size() - maxItems).append(" 条已省略，不要引用）\n");
                break;
            }
            AgentFinding f = findings.get(i);
            String claim = f.claim() == null ? "" : f.claim().replaceAll("\\s+", " ").trim();
            if (claim.length() > claimMax) {
                claim = claim.substring(0, claimMax) + "…";
            }
            sb.append("- [#").append(i + 1).append("] [").append(f.kind()).append("] ")
                    .append(claim).append('\n');
        }
        return sb.toString();
    }
}
