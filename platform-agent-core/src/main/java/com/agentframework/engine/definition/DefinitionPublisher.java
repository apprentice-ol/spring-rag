package com.agentframework.engine.definition;

import com.agentframework.definition.codec.DefinitionKind;
import com.agentframework.definition.codec.LoadResult;
import com.agentframework.extension.permission.PermissionChecker;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.persistence.DefinitionRecord;
import com.agentframework.runtime.persistence.DefinitionStatus;
import com.agentframework.runtime.persistence.DefinitionStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 定义发布器：在存储之上补上「入库前校验 + 发布权限 + 审计事件」三件事。
 *
 * <p>权限校验放在引擎层而不是存储层，是为了保持依赖方向：存储只负责读写，
 * 治理属于引擎的编排职责。</p>
 */
public final class DefinitionPublisher {

    /** 发布定义所需权限。 */
    public static final String PERMISSION_PUBLISH = "definition:publish";

    /** 归档定义所需权限。 */
    public static final String PERMISSION_ARCHIVE = "definition:archive";

    private final DefinitionStore store;
    private final PermissionChecker permissions;
    private final EventBus events;

    /**
     * @param store       定义存储
     * @param permissions 权限校验器，可为 null 表示不校验
     * @param events      事件总线，可为 null 表示不发审计事件
     */
    public DefinitionPublisher(DefinitionStore store, PermissionChecker permissions, EventBus events) {
        if (store == null) {
            throw new IllegalArgumentException("definition store is required");
        }
        this.store = store;
        this.permissions = permissions;
        this.events = events;
    }

    /**
     * 保存草稿：只有通过校验（无 ERROR）的定义才能入库。
     *
     * @param kind   定义种类
     * @param result 加载结果
     * @param author 保存者
     * @return 草稿记录
     */
    public DefinitionRecord saveDraft(DefinitionKind kind, LoadResult result, String author) {
        if (result == null || !result.accepted()) {
            throw new IllegalArgumentException("定义未通过校验，不能入库："
                    + (result == null ? "结果为空" : result.report().errorMessages()));
        }
        Map<String, Object> document = result.document();
        DefinitionRecord record = new DefinitionRecord(kind, stringField(document, "id"),
                stringField(document, "version"), DefinitionStatus.DRAFT, document, result.report(), author,
                Instant.now(), null);
        store.save(record);
        publish(Topics.DEFINITION_SAVED, record, "save");
        return record;
    }

    /**
     * 发布定义：要求存在通过校验的草稿，且版本号必须显式。
     *
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号
     * @param actor   操作者
     * @return 已发布记录
     */
    public DefinitionRecord publish(DefinitionKind kind, String id, String version, String actor) {
        require(PERMISSION_PUBLISH, actor);
        if (version == null || version.isBlank() || "latest".equals(version)) {
            throw new IllegalArgumentException(
                    "发布必须使用显式版本号（DEFINITION_VERSION_REQUIRED）：" + version);
        }
        DefinitionRecord draft = store.find(kind, id, version, DefinitionStatus.DRAFT)
                .orElseThrow(() -> new NoSuchElementException(
                        "没有找到草稿：" + kind.wireName() + ":" + id + "@" + version));
        if (draft.report().hasErrors()) {
            throw new IllegalStateException("草稿仍有阻断性问题，不能发布：" + draft.report().errorMessages());
        }
        DefinitionRecord published = draft.withStatus(DefinitionStatus.PUBLISHED)
                .withGovernance(actor, Instant.now());
        store.save(published);
        publish(Topics.DEFINITION_PUBLISHED, published, "publish");
        return published;
    }

    /**
     * 归档定义。
     *
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号
     * @param actor   操作者
     * @return 归档记录
     */
    public DefinitionRecord archive(DefinitionKind kind, String id, String version, String actor) {
        require(PERMISSION_ARCHIVE, actor);
        DefinitionRecord published = store.find(kind, id, version, DefinitionStatus.PUBLISHED)
                .orElseThrow(() -> new NoSuchElementException(
                        "没有找到已发布版本：" + kind.wireName() + ":" + id + "@" + version));
        DefinitionRecord archived = published.withStatus(DefinitionStatus.ARCHIVED);
        // 归档即下线：移除已发布记录，避免引擎继续加载
        store.delete(kind, id, version, DefinitionStatus.PUBLISHED);
        store.save(archived);
        publish(Topics.DEFINITION_ARCHIVED, archived, "archive");
        return archived;
    }

    /**
     * @param document 文档
     * @param field    字段名
     * @return 字段值，缺失时返回 latest
     */
    private String stringField(Map<String, Object> document, String field) {
        Object value = document.get(field);
        return value == null || String.valueOf(value).isBlank() ? "latest" : String.valueOf(value);
    }

    /**
     * @param permission 权限
     * @param actor      操作者
     */
    private void require(String permission, String actor) {
        if (permissions != null) {
            permissions.require(permission, actor == null ? "definition-publisher" : actor);
        }
    }

    /**
     * @param type   事件类型
     * @param record 记录
     * @param action 动作
     */
    private void publish(String type, DefinitionRecord record, String action) {
        if (events == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", record.kind().wireName());
        payload.put("id", record.id());
        payload.put("version", record.version());
        payload.put("status", record.status().name());
        payload.put("action", action);
        payload.put("actor", record.author());
        payload.put("errors", record.report().errors().size());
        payload.put("warnings", record.report().warnings().size());
        events.publish(Event.of(type, record.key(), payload));
    }
}
