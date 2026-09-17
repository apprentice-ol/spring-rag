package com.jjx.customer.platform.prompt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.prompt.entity.PromptBundleEntity;
import com.jjx.customer.platform.prompt.entity.PromptBundleReleaseEntity;
import com.jjx.customer.platform.prompt.entity.PromptVersionEntity;
import com.jjx.customer.platform.prompt.mapper.PromptBundleMapper;
import com.jjx.customer.platform.prompt.mapper.PromptBundleReleaseMapper;
import com.jjx.customer.platform.prompt.mapper.PromptMapper;
import com.jjx.customer.platform.prompt.mapper.PromptVersionMapper;
import com.jjx.customer.platform.common.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prompt 能力包管理服务（P2：组包 → 发布 release → 绑定切换的 console 后端）。
 *
 * <p>release 不可变：发布时把 {key: versionId} 固化为全量快照——此后 key 的版本演进不影响已发布 release；
 * 改包 = 挑新版本再发一版。包可复制（fork 后改组再发，是"标准包 → 精简包"工作流的基础）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBundleService {

    private final PromptBundleMapper bundleMapper;
    private final PromptBundleReleaseMapper releaseMapper;
    private final PromptMapper promptMapper;
    private final PromptVersionMapper versionMapper;
    private final PromptBindingService bindingService;
    private final ObjectMapper objectMapper;

    /** 包视图（含最新 release 号）。 */
    public record BundleView(Long id, String name, String description, String agentType,
                             Integer latestRelease, LocalDateTime updateTime) {
    }

    /** 建包。 */
    @Transactional
    public PromptBundleEntity createBundle(String name, String description, String agentType) {
        if (!StringUtils.hasText(name)) {
            throw new ClientException("包名不能为空");
        }
        PromptBundleEntity exists = bundleMapper.selectOne(new LambdaQueryWrapper<PromptBundleEntity>()
                .eq(PromptBundleEntity::getName, name));
        if (exists != null) {
            throw new ClientException("包名已存在: " + name);
        }
        PromptBundleEntity b = new PromptBundleEntity();
        b.setName(name);
        b.setDescription(description);
        b.setAgentType(StringUtils.hasText(agentType) ? agentType : null);
        bundleMapper.insert(b);
        return b;
    }

    /** 包列表（含最新 release 号）。 */
    public List<BundleView> listBundles() {
        return bundleMapper.selectList(new LambdaQueryWrapper<PromptBundleEntity>()
                        .orderByAsc(PromptBundleEntity::getId))
                .stream().map(b -> new BundleView(b.getId(), b.getName(), b.getDescription(), b.getAgentType(),
                        latestReleaseNo(b.getId()), b.getUpdateTime()))
                .toList();
    }

    /** 复制包（fork：连最新 release 的 items 一起拷，改组后再发新版——标准包→精简包工作流）。 */
    @Transactional
    public PromptBundleEntity forkBundle(Long sourceId, String newName) {
        PromptBundleEntity src = requireBundle(sourceId);
        PromptBundleEntity copy = createBundle(newName,
                "fork 自 " + src.getName() + (src.getDescription() != null ? "：" + src.getDescription() : ""),
                src.getAgentType());
        Integer latest = latestReleaseNo(sourceId);
        if (latest != null) {
            PromptBundleReleaseEntity srcRelease = requireRelease(sourceId, latest);
            publishReleaseItems(copy.getId(), parseItems(srcRelease.getItems()),
                    "fork 自 " + src.getName() + "@r" + latest);
        }
        return copy;
    }

    /**
     * 删除包（连同其全部 release）。
     *
     * <p>有绑定时拒绝：绑定是活引用，删掉会让骨架装配静默回退基线，属于"配置悄悄失效"——
     * 必须先解绑，让用户显式知道这个骨架不再用包。prompt 资产本身（sa_prompt / sa_prompt_version）
     * 不受影响，release 只是指向某些版本的快照。</p>
     */
    @Transactional
    public void deleteBundle(Long bundleId) {
        PromptBundleEntity b = requireBundle(bundleId);
        List<String> boundAgents = bindingService.list().stream()
                .filter(x -> bundleId.equals(x.getBaseBundleId()) || bundleId.equals(x.getOverlayBundleId()))
                .map(com.jjx.customer.platform.prompt.entity.PromptBindingEntity::getAgentType)
                .toList();
        if (!boundAgents.isEmpty()) {
            throw new ClientException("包「" + b.getName() + "」仍被绑定，请先解绑: " + String.join("、", boundAgents));
        }
        int releases = releaseMapper.delete(new LambdaQueryWrapper<PromptBundleReleaseEntity>()
                .eq(PromptBundleReleaseEntity::getBundleId, bundleId));
        bundleMapper.deleteById(bundleId);
        log.info("删除能力包 id={} name={}，连带 {} 条 release", bundleId, b.getName(), releases);
    }

    /**
     * 发布 release：把 {key: versionNo} 固化为 {key: versionId} 全量快照。
     * items 形如 {"agent/ops/resolve": 3, ...}（版本号对；key 必须已建档）。
     */
    @Transactional
    public int publishRelease(Long bundleId, Map<String, Integer> itemsByVersionNo, String changeNote) {
        if (itemsByVersionNo == null || itemsByVersionNo.isEmpty()) {
            throw new ClientException("release 内容不能为空");
        }
        Map<String, Long> items = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : itemsByVersionNo.entrySet()) {
            var prompt = promptMapper.selectOne(new LambdaQueryWrapper<com.jjx.customer.platform.prompt.entity.PromptEntity>()
                    .eq(com.jjx.customer.platform.prompt.entity.PromptEntity::getPromptKey, e.getKey()));
            if (prompt == null) {
                throw new ClientException("prompt key 未建档: " + e.getKey());
            }
            PromptVersionEntity v = versionMapper.selectOne(new LambdaQueryWrapper<PromptVersionEntity>()
                    .eq(PromptVersionEntity::getPromptId, prompt.getId())
                    .eq(PromptVersionEntity::getVersionNo, e.getValue()));
            if (v == null) {
                throw new ClientException("版本不存在: " + e.getKey() + " v" + e.getValue());
            }
            items.put(e.getKey(), v.getId());
        }
        return publishReleaseItems(bundleId, items, changeNote);
    }

    /** 发布 release（内部：items 已是 versionId 形态）。 */
    @Transactional
    int publishReleaseItems(Long bundleId, Map<String, Long> items, String changeNote) {
        requireBundle(bundleId);
        Integer max = releaseMapper.maxReleaseNo(bundleId);
        int next = (max == null ? 0 : max) + 1;
        PromptBundleReleaseEntity r = new PromptBundleReleaseEntity();
        r.setBundleId(bundleId);
        r.setReleaseNo(next);
        try {
            r.setItems(objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            throw new IllegalStateException("items 序列化失败", e);
        }
        r.setChangeNote(StringUtils.hasText(changeNote) ? changeNote : "");
        releaseMapper.insert(r);
        return next;
    }

    /**
     * release 视图：items 从存储形态 {key: versionId} 解析为 {key: versionNo}。
     *
     * <p>存储仍用 versionId（不可变快照，key 后续发新版也不影响已发布 release），
     * 但 versionId 是全局自增主键，对用户没有意义——展示要的是"这个 prompt 的第几版"。
     * 解析走一次 {@code selectBatchIds} 批量查，不按 key 循环查库。</p>
     */
    public record ReleaseView(Long id, Long bundleId, Integer releaseNo, Map<String, Integer> items,
                              String changeNote, LocalDateTime createTime) {
    }

    /** release 历史（新→旧，items 为 {key: versionNo}，供前端展示包里具体装了什么）。 */
    public List<ReleaseView> releaseViews(Long bundleId) {
        List<PromptBundleReleaseEntity> rows = releases(bundleId);
        Map<Long, Map<String, Long>> byRelease = new LinkedHashMap<>();
        Set<Long> versionIds = new HashSet<>();
        for (PromptBundleReleaseEntity r : rows) {
            Map<String, Long> items = parseItems(r.getItems());
            byRelease.put(r.getId(), items);
            versionIds.addAll(items.values());
        }
        Map<Long, Integer> noById = versionIds.isEmpty() ? Map.of()
                : versionMapper.selectBatchIds(versionIds).stream()
                        .collect(Collectors.toMap(PromptVersionEntity::getId, PromptVersionEntity::getVersionNo));
        return rows.stream().map(r -> {
            Map<String, Integer> items = new LinkedHashMap<>();
            byRelease.get(r.getId()).forEach((key, vid) -> items.put(key, noById.get(vid)));
            return new ReleaseView(r.getId(), r.getBundleId(), r.getReleaseNo(), items,
                    r.getChangeNote(), r.getCreateTime());
        }).toList();
    }

    /** release 历史（新→旧）。 */
    public List<PromptBundleReleaseEntity> releases(Long bundleId) {
        return releaseMapper.selectList(new LambdaQueryWrapper<PromptBundleReleaseEntity>()
                .eq(PromptBundleReleaseEntity::getBundleId, bundleId)
                .orderByDesc(PromptBundleReleaseEntity::getReleaseNo));
    }

    /** 绑定切换（装配校验在 PromptBindingService.bind 内即时反馈）。 */
    public void bind(String agentType, Long baseBundleId, Long overlayBundleId) {
        bindingService.bind(agentType, baseBundleId, overlayBundleId);
    }

    public void unbind(String agentType) {
        bindingService.unbind(agentType);
    }

    /** 全部绑定视图。 */
    public List<com.jjx.customer.platform.prompt.entity.PromptBindingEntity> bindings() {
        return bindingService.list();
    }

    // ==================== 内部 ====================

    private Integer latestReleaseNo(Long bundleId) {
        return releaseMapper.maxReleaseNo(bundleId);
    }

    private PromptBundleEntity requireBundle(Long id) {
        PromptBundleEntity b = bundleMapper.selectById(id);
        if (b == null) {
            throw new ClientException("bundle 不存在: " + id);
        }
        return b;
    }

    private PromptBundleReleaseEntity requireRelease(Long bundleId, int releaseNo) {
        PromptBundleReleaseEntity r = releaseMapper.selectOne(new LambdaQueryWrapper<PromptBundleReleaseEntity>()
                .eq(PromptBundleReleaseEntity::getBundleId, bundleId)
                .eq(PromptBundleReleaseEntity::getReleaseNo, releaseNo));
        if (r == null) {
            throw new ClientException("release 不存在: bundle=" + bundleId + " r" + releaseNo);
        }
        return r;
    }

    private Map<String, Long> parseItems(String itemsJson) {
        try {
            Map<String, Object> raw = objectMapper.readValue(itemsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Map<String, Long> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, Long.valueOf(String.valueOf(v))));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("release items 解析失败", e);
        }
    }
}
