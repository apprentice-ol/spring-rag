package com.jjx.customer.platform.business.orchestration;

import com.jjx.customer.platform.conversation.ConversationStore;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.document.DocumentCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指代悬空检测与澄清（自 {@link ChatOrchestrator} 决策链第 2 步拆出）：
 * 「这篇文档」类指代在会话里找不到先行对象时反问澄清，
 * 而不是把"这篇"静默解释成检索第一名（检索到哪篇就总结哪篇，用户易被误导）。
 */
@Component
@RequiredArgsConstructor
public class DocReferenceClarifier<S> {

    private final ConversationStore conversationStore;
    private final DocumentCatalog documentCatalog;
    private final DeliveryPortFactory<S> deliveryPortFactory;

    /** 文档类指代词模式：这篇/该/上述/刚才的… + 文档/文件/文章/发票等（命中才做悬空判定，无命中零成本） */
    private static final java.util.regex.Pattern DANGLING_DOC_REF = java.util.regex.Pattern.compile(
            "(?:这篇|这封|这份|这则|该|上述|上面的|上面提到|刚才|刚刚|前面|之前)"
                    + "(?:一?(?:篇|封|份|则)|的)?\\s*"
                    + "(?:文档|文件|文章|资料|报告|报表|表格|发票|合同|手册|指南|规范)");

    /**
     * 「以文档为对象」的元问题模式：问题核心是某个文档容器的摘要/要点本身（总结文档、文档里的关键信息…），
     * 没有任何业务主题词。此类问题同样需要明确的文档对象，与「这篇文档」指代同等对待。
     */
    private static final java.util.regex.Pattern META_DOC_QUESTION = java.util.regex.Pattern.compile(
            "(?:总结|概括|概述|归纳|提炼|提取|梳理)\\s*一?下?"
                    + "|(?:关键信息|核心要点|主要内容|重点内容|信息要点|核心内容)"
                    + "|(?:讲了什么|说了什么|提到(?:了)?(?:哪些|什么)|包含(?:了)?(?:哪些|什么))");

    /** 含文档类名词（元问题判定的前提：问题里根本没有"文档"字样就不可能是文档元问题） */
    private static final java.util.regex.Pattern DOC_NOUN = java.util.regex.Pattern.compile(
            "文档|文件|文章|资料");

    /**
     * 指代悬空判定：消息含「这篇文档」类指代、或是以文档为对象的元问题（无业务主题），但会话里找不到先行对象。
     * <p>先行对象 = 此前 user 消息中出现过库内任一文档名（全名或去扩展名主干）。
     * 新会话（此前无 user 消息）必悬空。仅正则命中时才做两次轻查询，正常消息零开销。</p>
     */
    public boolean isDanglingDocReference(String question, String conversationId) {
        if (question == null
                || (!DANGLING_DOC_REF.matcher(question).find() && !isMetaDocQuestion(question))) {
            return false;
        }
        // 当前 user 消息已在 execute 开头落库，count <= 1 说明这是会话首条 → 必悬空
        long priorUserMsgs = conversationStore.userMessageCount(conversationId);
        if (priorUserMsgs <= 1) {
            return true;
        }
        // 有历史：拼最近 user 消息文本，与库内文档名比对（含去扩展名主干，用户提名字常不带后缀）
        String recentUserText = conversationStore.recentUserText(conversationId, 10);
        List<String> docNames = documentCatalog.allDocumentNames();
        for (String name : docNames) {
            if (recentUserText.contains(name)) {
                return false;
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() > 4) {
                String stem = name.substring(0, dot);
                // 主干太短（如 "a"、"doc"）会大量误匹配，跳过
                if (stem.length() >= 4 && recentUserText.contains(stem)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 元问题判定：含文档类名词 + 命中元问题模式（总结/关键信息/讲了什么…）。
     * <p>例：「文档中提到了哪些关键信息」（空状态页建议词）、「总结一下这份文件」。
     * 反例（不拦）：「发票文档里提到了冲红流程」（"提到了"后跟具体主题，非"哪些/什么"泛问）、
     * 「总结一下增值税发票冲红」（不含文档名词，自带主题，正常检索）。</p>
     */
    private static boolean isMetaDocQuestion(String question) {
        return DOC_NOUN.matcher(question).find() && META_DOC_QUESTION.matcher(question).find();
    }

    /** 指代悬空的反问澄清（同诊断缺 traceId 的反问模式：落库 + 流式输出 + meta + complete）。 */
    public void askForDocClarification(String conversationId, S sink, String otelTraceId) {
        String ask = "你的问题指向某篇具体的文档，但我们当前的对话里还没有确定是哪一篇。\n\n"
                + "可以带上文档名提问，比如「总结《增值税发票冲红流程.pdf》」；"
                + "也可以描述得更具体一些（比如文档的主题、里面提到的内容），我来帮你定位。";
        deliveryPortFactory.begin(sink, conversationId, null, otelTraceId, null, null)
                .emitNotice(conversationId, ask, otelTraceId);
    }
}
