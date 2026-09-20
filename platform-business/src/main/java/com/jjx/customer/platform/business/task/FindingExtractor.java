package com.jjx.customer.platform.business.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从四段式交付格式的诊断结论里抽取主张——<b>纯解析，不耗模型</b>。
 *
 * <p>这是"四段式交付格式"的意外收益：改 prompt 时只想统一排版，结果把结论变成了
 * <b>可解析</b>的结构——{@code 证据链} 段本身就是「取值（来源）」列表，{@code 修正动作}
 * 是 JSON + 改动表。于是主张抽取从"再叫一次模型来归纳"退化成字符串切分，零延迟、零成本、
 * 可单测。</p>
 *
 * <p><b>解析失败不是错误。</b>结论可能因降级、截断或模型不守格式而缺段——那时少抽几条，
 * 而不是抛异常。调用方按"抽到几条就是几条"处理（见 {@code OpsRunner}）。</p>
 */
public final class FindingExtractor {

    private static final String MARK_CONCLUSION = "**排查结论**";
    private static final String MARK_EVIDENCE = "**证据链**";
    private static final String MARK_FIX = "**修正动作**";
    private static final String MARK_RISK = "**风险提醒**";
    private static final String MARK_CHANGES = "**主张变更**";

    /** 「主张变更」里的一条否定：{@code - #3 否定：<理由>}（也接受 推翻/retract 等写法）。 */
    private static final java.util.regex.Pattern DENY =
            java.util.regex.Pattern.compile("#\\s*(\\d+)\\s*(?:否定|推翻|retract|RETRACT)");

    /**
     * 解析「主张变更」段里被否定的主张编号。
     *
     * <p>编号对应注入时渲染的 {@code [#n]}（见 {@code OpsRunner.renderFindings}）。
     * 用<b>编号</b>而非文本相似度定位：语义判断交给模型，精确落位交回解析——
     * 模糊匹配迟早会把"哪条更像"判断错，而错标一条主张比不标更糟。</p>
     *
     * <p>该段是**可选**的：模型没输出（或没读懂用户意图）就返回空集，
     * 此时主张照常被新结论整体取代（{@code SUPERSEDED}），只是少了"被否定"这层语义。</p>
     *
     * @param rawConclusion 结论原文（含或不含服务端追加的上下文段均可）
     * @return 被否定的编号集合（1-based）；无该段 = 空集
     */
    public static Set<Integer> deniedIndices(String rawConclusion) {
        String block = section(stripAutoNote(rawConclusion), MARK_CHANGES);
        Set<Integer> denied = new LinkedHashSet<>();
        for (String line : bullets(block)) {
            java.util.regex.Matcher m = DENY.matcher(line);
            if (m.find()) {
                denied.add(Integer.parseInt(m.group(1)));
            }
        }
        return denied;
    }

    /** 服务端追加的上下文段标记：它不是结论的一部分，抽取前必须切掉。 */
    private static final String AUTO_NOTE_MARK = "本次诊断的上下文自动补全";

    private FindingExtractor() {
    }

    /**
     * 抽取主张。
     *
     * @param taskId         任务标识
     * @param conversationId 对话标识
     * @param attemptNo      产出本次结论的 attempt 序号
     * @param rawConclusion  结论原文（含或不含服务端追加的上下文段均可）
     * @return 主张列表（无格式/空文本 = 空列表，不抛异常）
     */
    public static List<AgentFinding> extract(String taskId, String conversationId,
                                             int attemptNo, String rawConclusion) {
        String text = stripAutoNote(rawConclusion);
        if (text.isBlank()) {
            return List.of();
        }
        String conclusion = section(text, MARK_CONCLUSION);
        String evidenceBlock = section(text, MARK_EVIDENCE);
        String fixBlock = section(text, MARK_FIX);
        String riskBlock = section(text, MARK_RISK);

        List<AgentFinding> findings = new ArrayList<>();
        // 根因：证据链整段挂在它下面（证据是为这个断言服务的）
        if (!conclusion.isBlank()) {
            findings.add(AgentFinding.active(id(), taskId, conversationId,
                    AgentFinding.Kind.ROOT_CAUSE, conclusion, parseEvidence(evidenceBlock), attemptNo));
        }
        if (!fixBlock.isBlank()) {
            findings.add(AgentFinding.active(id(), taskId, conversationId,
                    AgentFinding.Kind.FIX, fixBlock, List.of(), attemptNo));
        }
        // 风险逐条成主张——用户可能只否定其中某一条（"重复红冲没风险"），粒度粗了就没法局部否定
        for (String risk : bullets(riskBlock)) {
            findings.add(AgentFinding.active(id(), taskId, conversationId,
                    AgentFinding.Kind.RISK, risk, List.of(), attemptNo));
        }
        return findings;
    }

    /** 切掉服务端追加的「上下文自动补全」段（它是执行元数据，不是诊断结论）。 */
    private static String stripAutoNote(String raw) {
        if (raw == null) {
            return "";
        }
        int mark = raw.indexOf(AUTO_NOTE_MARK);
        if (mark < 0) {
            return raw.trim();
        }
        int dash = raw.lastIndexOf("——", mark);
        return raw.substring(0, dash < 0 ? mark : dash).trim();
    }

    /** 全部段标记（按格式中的顺序）。 */
    private static final String[] ALL_MARKS = {
            MARK_CONCLUSION, MARK_EVIDENCE, MARK_FIX, MARK_RISK, MARK_CHANGES};

    /**
     * 取某段正文：从该段标记到**下一个出现的任意段标记**为止。标记缺失返回空串
     * （缺段 = 少抽几条，不是异常）。
     *
     * <p>结尾必须按"任意段标记"而非"下一个特定段标记"来定：模型偶尔漏段（比如没有证据链），
     * 若写死用 {@code MARK_EVIDENCE} 当结尾，缺了它的时候「排查结论」会把后面所有段一起吞掉——
     * 变更段、风险段全成了结论正文。</p>
     */
    private static String section(String text, String startMark) {
        int start = text.indexOf(startMark);
        if (start < 0) {
            return "";
        }
        start += startMark.length();
        int end = text.length();
        for (String mark : ALL_MARKS) {
            int next = text.indexOf(mark, start);
            if (next >= 0 && next < end) {
                end = next;
            }
        }
        return text.substring(start, end).trim();
    }

    /**
     * 解析证据条目：{@code - 标签：取值（来源）}。
     *
     * <p>取值本身可能含全角括号（如报文里的中文），所以来源取**最后一对**括号——
     * 从左边找会把取值截断。</p>
     */
    private static List<AgentFinding.Evidence> parseEvidence(String block) {
        List<AgentFinding.Evidence> items = new ArrayList<>();
        for (String line : bullets(block)) {
            int open = line.lastIndexOf('（');
            int close = line.lastIndexOf('）');
            String source = "";
            String head = line;
            if (open >= 0 && close > open) {
                // 归一化：实测模型写来源时有时带「来源：」前缀有时不带
                //（同一份输出里「接口」条不带、「时间」条带），不归一会让 source 字段两种形态混杂
                source = line.substring(open + 1, close).trim().replaceFirst("^来源[：:]\\s*", "");
                head = line.substring(0, open).trim();
            }
            int colon = head.indexOf('：');
            if (colon < 0) {
                colon = head.indexOf(':');
            }
            String label = colon < 0 ? head : head.substring(0, colon).trim();
            String value = colon < 0 ? "" : head.substring(colon + 1).trim();
            items.add(new AgentFinding.Evidence(label, value, source));
        }
        return items;
    }

    /**
     * 取列表段落的条目：每条以 {@code - } 起头，后续非空且非列表行视为该条的续行
     * （风险条常常换行书写，按行切开会把一句话拆成两条主张）。
     */
    private static List<String> bullets(String block) {
        List<String> items = new ArrayList<>();
        if (block == null || block.isBlank()) {
            return items;
        }
        StringBuilder current = null;
        for (String rawLine : block.split("\\R")) {
            String line = rawLine.trim();
            if (line.startsWith("- ")) {
                if (current != null) {
                    items.add(current.toString());
                }
                current = new StringBuilder(line.substring(2).trim());
            } else if (current != null && !line.isEmpty()) {
                current.append(' ').append(line);
            }
        }
        if (current != null) {
            items.add(current.toString());
        }
        return items;
    }

    private static String id() {
        return java.util.UUID.randomUUID().toString();
    }
}
