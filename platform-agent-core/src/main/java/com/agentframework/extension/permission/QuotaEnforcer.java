package com.agentframework.extension.permission;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配额执行器：累计每个会话的用量，并在超限时中断执行。
 *
 * <p>内核在每个节点、每次模型调用与工具调用后调用这里，把“配额”从文档变成可执行的约束。</p>
 */
public final class QuotaEnforcer {

    private final Map<String, QuotaUsage> usage = new ConcurrentHashMap<>();

    /**
     * @param sessionId 会话 id
     * @return 当前用量，未记录时返回空用量
     */
    public QuotaUsage usage(String sessionId) {
        return usage(sessionId, null);
    }

    /**
     * @param sessionId 会话 id
     * @param regionId  区域 id，null 表示 Agent 级
     * @return 当前用量，未记录时返回空用量
     */
    public QuotaUsage usage(String sessionId, String regionId) {
        return usage.computeIfAbsent(scopeKey(sessionId, regionId), QuotaUsage::empty);
    }

    /**
     * 记录 token 消耗。
     *
     * @param sessionId 会话 id
     * @param tokens    本次消耗
     * @param quota     配额
     * @throws QuotaExceededException 超限时抛出
     */
    public void consumeTokens(String sessionId, long tokens, Quota quota) {
        consumeTokens(sessionId, null, tokens, quota);
    }

    /**
     * 记录 token 消耗并校验指定作用域的上限。
     *
     * @param sessionId 会话 id
     * @param regionId  区域 id，null 表示 Agent 级
     * @param tokens    本次消耗
     * @param quota     配额
     */
    public void consumeTokens(String sessionId, String regionId, long tokens, Quota quota) {
        String key = scopeKey(sessionId, regionId);
        QuotaUsage updated = usage.compute(key, (scope, current) ->
                (current == null ? QuotaUsage.empty(key) : current).plusTokens(Math.max(0, tokens)));
        if (quota != null && quota.tokensLimited() && updated.tokens() > quota.maxTokens()) {
            throw new QuotaExceededException(sessionId, "tokens",
                    scopeLabel(regionId) + "已用 " + updated.tokens() + " / 上限 " + quota.maxTokens());
        }
    }

    /**
     * 记录一次节点执行。
     *
     * @param sessionId 会话 id
     * @param quota     配额
     * @throws QuotaExceededException 超限时抛出
     */
    public void consumeNode(String sessionId, Quota quota) {
        consumeNode(sessionId, null, quota);
    }

    /**
     * 记录一次节点执行并校验指定作用域的上限。
     *
     * @param sessionId 会话 id
     * @param regionId  区域 id，null 表示 Agent 级
     * @param quota     配额
     */
    public void consumeNode(String sessionId, String regionId, Quota quota) {
        String key = scopeKey(sessionId, regionId);
        QuotaUsage updated = usage.compute(key, (scope, current) ->
                (current == null ? QuotaUsage.empty(key) : current).plusNode());
        if (quota != null && quota.nodesLimited() && updated.nodes() > quota.maxNodes()) {
            throw new QuotaExceededException(sessionId, "nodes",
                    scopeLabel(regionId) + "已执行 " + updated.nodes() + " / 上限 " + quota.maxNodes());
        }
    }

    /**
     * 记录一次工具调用。
     *
     * @param sessionId 会话 id
     * @param quota     配额
     * @throws QuotaExceededException 超限时抛出
     */
    public void consumeToolCall(String sessionId, Quota quota) {
        consumeToolCall(sessionId, null, quota);
    }

    /**
     * 记录一次工具调用并校验指定作用域的上限。
     *
     * @param sessionId 会话 id
     * @param regionId  区域 id，null 表示 Agent 级
     * @param quota     配额
     */
    public void consumeToolCall(String sessionId, String regionId, Quota quota) {
        String key = scopeKey(sessionId, regionId);
        QuotaUsage updated = usage.compute(key, (scope, current) ->
                (current == null ? QuotaUsage.empty(key) : current).plusToolCall());
        if (quota != null && quota.toolCallsLimited() && updated.toolCalls() > quota.maxToolCalls()) {
            throw new QuotaExceededException(sessionId, "toolCalls",
                    scopeLabel(regionId) + "已调用 " + updated.toolCalls() + " / 上限 " + quota.maxToolCalls());
        }
    }

    /**
     * 记录墙钟耗时并校验。
     *
     * @param sessionId 会话 id
     * @param elapsed   已消耗时间
     * @param quota     配额
     * @throws QuotaExceededException 超限时抛出
     */
    public void checkWallTime(String sessionId, Duration elapsed, Quota quota) {
        checkWallTime(sessionId, null, elapsed, quota);
    }

    /**
     * 记录墙钟耗时并校验指定作用域的上限。
     *
     * @param sessionId 会话 id
     * @param regionId  区域 id，null 表示 Agent 级
     * @param elapsed   已消耗时间
     * @param quota     配额
     */
    public void checkWallTime(String sessionId, String regionId, Duration elapsed, Quota quota) {
        String key = scopeKey(sessionId, regionId);
        if (elapsed != null) {
            usage.compute(key, (scope, current) ->
                    (current == null ? QuotaUsage.empty(key) : current).withWallTime(elapsed));
        }
        if (quota != null && quota.wallTimeLimited() && elapsed != null
                && elapsed.compareTo(quota.maxWallTime()) > 0) {
            throw new QuotaExceededException(sessionId, "wallTime",
                    scopeLabel(regionId) + "已耗时 " + elapsed.toMillis() + " ms / 上限 "
                            + quota.maxWallTime().toMillis() + " ms");
        }
    }

    /**
     * 清理会话用量。
     *
     * @param sessionId 会话 id
     */
    public void reset(String sessionId) {
        reset(sessionId, null);
    }

    /**
     * 清理指定作用域的用量。
     *
     * @param sessionId 会话 id
     * @param regionId  区域 id，null 表示 Agent 级
     */
    public void reset(String sessionId, String regionId) {
        usage.remove(scopeKey(sessionId, regionId));
    }

    /** @return 当前跟踪的会话数量 */
    public int trackedSessions() {
        return usage.size();
    }

    /**
     * @param sessionId 会话 id
     * @param regionId  区域 id
     * @return 作用域键
     */
    private String scopeKey(String sessionId, String regionId) {
        return regionId == null || regionId.isBlank() ? sessionId : sessionId + "::" + regionId;
    }

    /**
     * @param regionId 区域 id
     * @return 异常详情中的作用域前缀
     */
    private String scopeLabel(String regionId) {
        return regionId == null || regionId.isBlank() ? "" : "区域 '" + regionId + "' ";
    }
}
