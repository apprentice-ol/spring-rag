package com.agentframework.runtime.slot;

/**
 * 槽位编解码扩展点：把任意槽位值转换为可存储的字符串，并能反解回对象。
 *
 * <p>文件型存储依赖它落盘；数据库型存储可以换成 JSON 实现而不动内核。</p>
 */
public interface SlotCodec {

    /**
     * 编码槽位值。
     *
     * @param value 槽位值
     * @return 编码后的字符串
     */
    String encode(Object value);

    /**
     * 解码槽位值。
     *
     * @param payload 编码后的字符串
     * @param type    声明的类型名，用于还原数字 / 布尔等基础类型
     * @return 解码后的值
     */
    Object decode(String payload, String type);

    /** @return 编码后字符串的类型标记 */
    String typeOf(Object value);
}
