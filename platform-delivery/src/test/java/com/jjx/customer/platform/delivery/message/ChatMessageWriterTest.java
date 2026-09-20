package com.jjx.customer.platform.delivery.message;

import com.jjx.customer.platform.conversation.ConversationStore.HistorySpec;
import com.jjx.customer.platform.delivery.message.entity.MessageEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史取法的纯函数单测（窗口定界 + 预算渲染）。
 *
 * <p><b>为什么值得测</b>：这段逻辑错了不会报错，只会静默答偏——多带一轮少带一轮，
 * 现象是"模型偶尔认不出代词"，跟模型本身的波动混在一起，事后极难归因。</p>
 *
 * <p><b>覆盖边界</b>：这里只覆盖定界与渲染两个纯函数。两段 SQL 自身的边界
 * （{@code id < 当轮} / {@code id >= 下界}）不在纯函数里，按本项目惯例在真实 PG 上验证。</p>
 */
class ChatMessageWriterTest {

    /** 截断标注（与实现同字面量）——最新的那条超预算时，窗口里只留它并带上这句。 */
    private static final String TRUNCATION_NOTE = "……（此条过长已截断）";

    // ===== floorIdOf：窗口下界 =====

    /** 候选是倒序的（最新的在前），下界要的是<b>最早</b>那条，即末位——取成首位会退化成"只有最近一轮"。 */
    @Test
    void 下界取候选里最早的一条() {
        List<MessageEntity> descending = List.of(msg(9, "user", "第三问"), msg(5, "user", "第二问"), msg(1, "user", "第一问"));

        assertEquals(1L, ChatMessageWriter.floorIdOf(descending));
    }

    /** 上界之前一条用户消息都没有 = 本轮是会话首条，没有历史可言（不是"空窗口"，是"没有窗口"）。 */
    @Test
    void 候选为空表示无历史() {
        assertNull(ChatMessageWriter.floorIdOf(List.of()));
        assertNull(ChatMessageWriter.floorIdOf(null));
    }

    // ===== render：渲染 =====

    @Test
    void 空窗口渲染为空串() {
        assertEquals("", ChatMessageWriter.render(List.of(), 500));
        assertEquals("", ChatMessageWriter.render(null, 500));
    }

    /** 角色要中文化（prompt 里是「用户：」「助手：」），顺序即对话顺序。 */
    @Test
    void 渲染把角色中文化并按对话顺序拼接() {
        String rendered = ChatMessageWriter.render(List.of(
                msg(1, "user", "Redis 怎么配置"),
                msg(2, "assistant", "改 redis.conf 的 maxmemory"),
                msg(3, "user", "它的持久化呢")), 500);

        assertEquals("历史对话：\n用户：Redis 怎么配置\n\n助手：改 redis.conf 的 maxmemory\n\n用户：它的持久化呢\n\n",
                rendered);
    }

    /**
     * 半轮窗口（用户问了但助手没回——取消生成 / 落库失败 / 空检索降级都会留下这种形状）
     * 照常渲染，不吞消息也不抛异常。窗口定界之所以按"第 N 条 user 消息"而不是按条数，
     * 就是为了让这种形状不会把一整轮历史挤掉（见 ChatMessageWriter#historyContext）。
     */
    @Test
    void 缺助手回复的半轮照常渲染() {
        String rendered = ChatMessageWriter.render(List.of(
                msg(1, "user", "前面这个问题"),
                msg(2, "assistant", ""),
                msg(3, "user", "接着问的")), 500);

        assertEquals("历史对话：\n用户：前面这个问题\n\n助手：\n\n用户：接着问的\n\n", rendered);
    }

    /**
     * 预算不够时<b>从最早的整条丢弃</b>，而不是把它截半句。
     *
     * <p>半截内容比没有更糟：历史会同时被当成"操作对象"（翻译/总结上一轮回复），
     * 模型拿到半句话仍会照做，用户看到的却是干了一半的活。</p>
     */
    @Test
    void 预算不够时从最早的整条丢弃而不截半句() {
        String first = "甲".repeat(100);
        String second = "乙".repeat(100);
        String third = "丙".repeat(100);
        // 每条渲染后 = 角色前缀 3 + 内容 100 + 分隔 2 = 105；预算 220 恰好装下最新两条
        String rendered = ChatMessageWriter.render(List.of(
                msg(1, "user", first),
                msg(2, "assistant", second),
                msg(3, "user", third)), 220);

        assertEquals("历史对话：\n"
                + "助手：" + second + "\n\n"
                + "用户：" + third + "\n\n", rendered);
        assertFalse(rendered.contains(first), "装不下的更早一条应整条丢弃");
    }

    /**
     * 最新一条自己就超预算：只能截它（不截窗口就空了），但必须<b>写明截过</b>——
     * 丢一个光秃秃的省略号，模型会当成"原文就结束在这"，然后基于半截内容作答
     * （实测：703 字的回答被砍在第 4 条中间，模型只好回一句"原文被截断了"）。
     */
    @Test
    void 最新一条超预算时截断并写明截断() {
        String longAnswer = "答".repeat(500);
        // 预算 100 = 前缀 3 + 正文 86 + 标注 11
        String rendered = ChatMessageWriter.render(
                List.of(msg(1, "user", "问题"), msg(2, "assistant", longAnswer)), 100);

        assertEquals("历史对话：\n助手：" + "答".repeat(86) + TRUNCATION_NOTE + "\n\n", rendered);
        assertFalse(rendered.contains("问题"), "预算被最新一条吃满后，更早的整条丢弃");
    }

    /** 内容为 null 的消息不该让整段历史崩掉（渲染成空串，其余照常）。 */
    @Test
    void 内容为null渲染成空串() {
        String rendered = ChatMessageWriter.render(List.of(msg(1, "assistant", null)), 100);

        assertTrue(rendered.contains("助手："), "角色前缀照常渲染，实际=" + rendered);
    }

    // ===== HistorySpec：规格收敛 =====

    /** 配置写 0 / 负数时收敛到 1：不能变成"取不到历史"，也不能让预算变成"输出全空"。 */
    @Test
    void 规格把非法的轮数与预算收敛到正数() {
        HistorySpec spec = HistorySpec.of(7L, 0, -5);

        assertEquals(1, spec.rounds());
        assertEquals(1, spec.totalChars());
        assertEquals(7L, spec.beforeMessageId());
    }

    private static MessageEntity msg(long id, String role, String content) {
        MessageEntity entity = new MessageEntity();
        entity.setId(id);
        entity.setRole(role);
        entity.setContent(content);
        return entity;
    }
}
