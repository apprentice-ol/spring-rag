package com.agentframework.engine.core;

import com.agentframework.runtime.session.Message;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行结果。
 *
 * @param nodeId     节点 id
 * @param status     执行状态
 * @param output     文本输出
 * @param slotWrites 需要写回槽位的键值对
 * @param nextNodeId 显式指定的下一个节点（条件节点使用），null 表示按边推导
 * @param dynamicNextNodeId 节点自主选择的下一个节点（动态边），null 表示无自主选择
 * @param messages   需要追加到会话的消息
 * @param metadata   附加信息（用量、缓存命中等）
 * @param error      失败原因
 */
public record NodeResult(
        String nodeId,
        NodeStatus status,
        String output,
        Map<String, Object> slotWrites,
        String nextNodeId,
        String dynamicNextNodeId,
        List<Message> messages,
        Map<String, Object> metadata,
        String error) {

    public NodeResult {
        status = status == null ? NodeStatus.COMPLETED : status;
        output = output == null ? "" : output;
        slotWrites = slotWrites == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(slotWrites));
        messages = List.copyOf(messages == null ? List.of() : messages);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param nodeId 节点 id
     * @param output 文本输出
     * @return 执行完成结果
     */
    public static NodeResult completed(String nodeId, String output) {
        return new NodeResult(nodeId, NodeStatus.COMPLETED, output, null, null, null, null, null, null);
    }

    /**
     * @param nodeId 节点 id
     * @param output 文本输出
     * @param slotWrites 写回的槽位
     * @return 执行完成结果
     */
    public static NodeResult completed(String nodeId, String output, Map<String, Object> slotWrites) {
        return new NodeResult(nodeId, NodeStatus.COMPLETED, output, slotWrites, null, null, null, null, null);
    }

    /**
     * @param nodeId 节点 id
     * @param prompt 挂起原因（通常是需要人工回答的问题）
     * @return 挂起结果
     */
    public static NodeResult suspended(String nodeId, String prompt) {
        return new NodeResult(nodeId, NodeStatus.SUSPENDED, prompt, null, null, null, null, null, null);
    }

    /**
     * @param nodeId 节点 id
     * @param reason 失败原因
     * @return 失败结果
     */
    public static NodeResult failed(String nodeId, String reason) {
        return new NodeResult(nodeId, NodeStatus.FAILED, "", null, null, null, null, null, reason);
    }


    /** 这个目前是有条件执行器进行判断的
     * @param nodeId 节点 id
     * @param target 下一个节点 id
     * @return 路由结果
     */
    public static NodeResult route(String nodeId, String target) {
        return new NodeResult(nodeId, NodeStatus.SKIPPED, "", null, target, null, null, null, null);
    }

    /**
     * 构造动态路由结果：节点自主选择下一个节点，是否被采纳由运行时按白名单判定。
     *
     * @param nodeId 节点 id
     * @param output 文本输出
     * @param target 自主选择的下一个节点
     * @return 动态路由结果
     */
    public static NodeResult dynamic(String nodeId, String output, String target) {
        return new NodeResult(nodeId, NodeStatus.COMPLETED, output, null, null, target, null, null, null);
    }

    /** @return 是否执行完成 */
    public boolean isCompleted() {
        return status == NodeStatus.COMPLETED;
    }

    /** @return 是否挂起 */
    public boolean isSuspended() {
        return status == NodeStatus.SUSPENDED;
    }

    /** @return 是否失败 */
    public boolean isFailed() {
        return status == NodeStatus.FAILED;
    }

    /**
     * @param newOutput 新的文本输出
     * @return 替换输出后的结果
     */
    public NodeResult withOutput(String newOutput) {
        return new NodeResult(nodeId, status, newOutput, slotWrites, nextNodeId, dynamicNextNodeId, messages, metadata,
                error);
    }

    /**
     * @param target 自主选择的下一个节点
     * @return 替换动态目标后的结果
     */
    public NodeResult withDynamicNext(String target) {
        return new NodeResult(nodeId, status, output, slotWrites, nextNodeId, target, messages, metadata, error);
    }

    /**
     * @param key   槽位名
     * @param value 槽位值
     * @return 追加槽位写入后的结果
     */
    public NodeResult withSlotWrite(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(slotWrites);
        merged.put(key, value);
        return new NodeResult(nodeId, status, output, merged, nextNodeId, dynamicNextNodeId, messages, metadata, error);
    }

    /**
     * @param message 追加的消息
     * @return 追加消息后的结果
     */
    public NodeResult withMessage(Message message) {
        List<Message> merged = new java.util.ArrayList<>(messages);
        merged.add(message);
        return new NodeResult(nodeId, status, output, slotWrites, nextNodeId, dynamicNextNodeId, merged, metadata,
                error);
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 追加元数据后的结果
     */
    public NodeResult withMetadata(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new NodeResult(nodeId, status, output, slotWrites, nextNodeId, dynamicNextNodeId, messages, merged,
                error);
    }
}
