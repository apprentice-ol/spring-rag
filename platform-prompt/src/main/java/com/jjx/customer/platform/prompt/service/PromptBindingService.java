package com.jjx.customer.platform.prompt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.agent.framework.prompt.PromptAssembler;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.prompt.entity.PromptBindingEntity;
import com.jjx.customer.platform.prompt.entity.PromptBundleEntity;
import com.jjx.customer.platform.prompt.entity.PromptBundleReleaseEntity;
import com.jjx.customer.platform.prompt.entity.PromptVersionEntity;
import com.jjx.customer.platform.prompt.mapper.PromptBindingMapper;
import com.jjx.customer.platform.prompt.mapper.PromptBundleMapper;
import com.jjx.customer.platform.prompt.mapper.PromptBundleReleaseMapper;
import com.jjx.customer.platform.prompt.mapper.PromptMapper;
import com.jjx.customer.platform.prompt.mapper.PromptVersionMapper;
import com.jjx.customer.platform.config.prompt.PromptStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 快照解析服务（P2 组合机制的装配方，业务侧实现——框架只消费快照）。
 *
 * <p>解析顺序（分层绑定链，P2 实现 agent 全局绑定 > 基线；请求级参数留位不开放）：
 * <ol>
 *   <li>无绑定 → <b>基线快照</b>：该骨架 requiredPromptKeys 的 classpath 内容
 *       （identity="基线包"，DB 版本管理面照常可用但未参与运行链路）；</li>
 *   <li>有绑定 → 基座包当前 release + 特化包当前 release <b>merge</b>（同 key 特化胜出）→
 *       版本内容 → requiredKeys 装配校验（缺即抛错带 key 名，绝不静默 fallback）→ 快照。</li>
 * </ol>
 *
 * <p><b>活包名 / 死指纹</b>：绑定存包名（每次装配解析当前 release，基座发版自动生效）；
 * 快照带 releasesSpec（bundleId:releaseNo 对，可重建）——eval 与会话恢复按 spec 精确回放，
 * {@link #resolveByReleases} 是会话粘性的实现（release 不可变故可复现；spec 失效回退当前绑定 + warn）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptBindingService {

    private final PromptBindingMapper bindingMapper;
    private final PromptBundleMapper bundleMapper;
    private final PromptBundleReleaseMapper releaseMapper;
    private final PromptMapper promptMapper;
    private final PromptVersionMapper versionMapper;
    private final PromptStore promptStore;
    private final AgentRegistry agentRegistry;
    private final ObjectMapper objectMapper;

    /** 按骨架解析生效快照（pipeline 装配 AgentTask 时调用；agentType 未注册回基线空集语义）。 */
    public PromptSnapshot resolve(String agentType) {
        PromptBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<PromptBindingEntity>()
                .eq(PromptBindingEntity::getAgentType, agentType));
        if (binding == null) {
            return baseline(agentType);
        }
        MergedRelease m = mergeBinding(binding);
        if (!m.hasAny() || m.items().isEmpty()) {
            return baseline(agentType); // 绑定存在但两包都无 release：等价未绑定
        }
        Map<String, String> contents = resolveContents(m.items());
        requireKeysCovered(agentType, contents);
        return new PromptSnapshot(contents, m.identity(),
                PromptAssembler.hashOf(contents), m.spec().isEmpty() ? null : m.spec());
    }

    /**
     * 生效覆盖视图（快照层接线用）：该 agentType 绑定解析出的 {key: content}（基座+特化 merge）。
     * 无绑定 / 两包无 release 返回空 map——快照层回退 classpath 内容，未绑定 agent 行为零变化。
     */
    public Map<String, String> overridesFor(String agentType) {
        PromptBindingEntity binding = bindingMapper.selectOne(new LambdaQueryWrapper<PromptBindingEntity>()
                .eq(PromptBindingEntity::getAgentType, agentType));
        if (binding == null) {
            return Map.of();
        }
        MergedRelease m = mergeBinding(binding);
        return m.items().isEmpty() ? Map.of() : resolveContents(m.items());
    }

    /**
     * 会话粘性：按 releasesSpec 精确重建当时的快照（release 不可变故可复现）。
     * spec 失效（包/发布被删）→ 回退当前绑定 + warn（会话行为可能切换，但绝不阻断）。
     */
    public PromptSnapshot resolveByReleases(String agentType, String releasesSpec) {
        if (releasesSpec == null || releasesSpec.isBlank()) {
            return resolve(agentType);
        }
        try {
            Map<String, Long> merged = new LinkedHashMap<>();
            StringBuilder identity = new StringBuilder();
            for (String part : releasesSpec.split("\\|")) {
                String[] idNo = part.split(":");
                long bundleId = Long.parseLong(idNo[0]);
                int releaseNo = Integer.parseInt(idNo[1]);
                PromptBundleEntity bundle = bundleMapper.selectById(bundleId);
                PromptBundleReleaseEntity release = bundle == null ? null
                        : releaseMapper.selectOne(new LambdaQueryWrapper<PromptBundleReleaseEntity>()
                                .eq(PromptBundleReleaseEntity::getBundleId, bundleId)
                                .eq(PromptBundleReleaseEntity::getReleaseNo, releaseNo));
                if (release == null) {
                    log.warn("[Prompt绑定] 会话粘住的 release 已失效({})，回退当前绑定: {}", part, agentType);
                    return resolve(agentType);
                }
                mergeItems(merged, parseItems(release.getItems()));
                if (identity.length() > 0) {
                    identity.append(" + ");
                }
                identity.append(bundle.getName()).append("@r").append(releaseNo);
            }
            Map<String, String> contents = resolveContents(merged);
            requireKeysCovered(agentType, contents);
            return new PromptSnapshot(contents, identity.toString(),
                    PromptAssembler.hashOf(contents), releasesSpec);
        } catch (Exception e) {
            log.warn("[Prompt绑定] releasesSpec 解析失败({})，回退当前绑定: {}", releasesSpec, e.getMessage());
            return resolve(agentType);
        }
    }

    /** 基线快照：骨架 requiredKeys 的 classpath 内容（无绑定时的默认路径）。 */
    public PromptSnapshot baseline(String agentType) {
        Map<String, String> contents = new LinkedHashMap<>();
        for (String key : requiredKeys(agentType)) {
            contents.put(key, promptStore.raw(key));
        }
        return new PromptSnapshot(contents, "基线包", PromptAssembler.hashOf(contents), null);
    }

    /** 绑定管理：设置/更新绑定（切换即生效——下一请求装配新快照；进行中会话不受影响）。 */
    public void bind(String agentType, Long baseBundleId, Long overlayBundleId) {
        // 切换前校验：merge 后覆盖 requiredKeys，缺即拒绝（绑定页即时反馈）
        PromptBindingEntity probe = new PromptBindingEntity();
        probe.setBaseBundleId(baseBundleId);
        probe.setOverlayBundleId(overlayBundleId);
        Map<String, String> contents = resolveContents(mergeBinding(probe).items());
        requireKeysCovered(agentType, contents);

        PromptBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<PromptBindingEntity>()
                .eq(PromptBindingEntity::getAgentType, agentType));
        if (existing == null) {
            PromptBindingEntity b = new PromptBindingEntity();
            b.setAgentType(agentType);
            b.setBaseBundleId(baseBundleId);
            b.setOverlayBundleId(overlayBundleId);
            bindingMapper.insert(b);
            return;
        }
        PromptBindingEntity upd = new PromptBindingEntity();
        upd.setId(existing.getId());
        upd.setBaseBundleId(baseBundleId);
        upd.setOverlayBundleId(overlayBundleId);
        bindingMapper.updateById(upd);
    }

    /** 解绑（回基线）。 */
    public void unbind(String agentType) {
        bindingMapper.delete(new LambdaQueryWrapper<PromptBindingEntity>()
                .eq(PromptBindingEntity::getAgentType, agentType));
    }

    /** 全部绑定视图（console 绑定管理页）。 */
    public List<PromptBindingEntity> list() {
        return bindingMapper.selectList(new LambdaQueryWrapper<PromptBindingEntity>()
                .orderByAsc(PromptBindingEntity::getAgentType));
    }

    // ==================== 内部 ====================

    /** 绑定的两层 merge 结果（基座→特化，同 key 特化胜出；identity/spec 供快照身份与回放）。 */
    private record MergedRelease(Map<String, Long> items, String identity, String spec, boolean hasAny) {
    }

    /** 基座当前 release → 特化当前 release 覆盖。 */
    private MergedRelease mergeBinding(PromptBindingEntity binding) {
        Map<String, Long> merged = new LinkedHashMap<>();
        StringBuilder identity = new StringBuilder();
        StringBuilder spec = new StringBuilder();
        boolean[] hasAny = {false};
        if (binding.getBaseBundleId() != null) {
            appendRelease(binding.getBaseBundleId(), merged, identity, spec, hasAny);
        }
        if (binding.getOverlayBundleId() != null) {
            appendRelease(binding.getOverlayBundleId(), merged, identity, spec, hasAny);
        }
        return new MergedRelease(merged, identity.toString(), spec.toString(), hasAny[0]);
    }

    private java.util.Set<String> requiredKeys(String agentType) {
        return agentRegistry.byId(agentType).map(agent -> {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>(agent.promptKeys());
            keys.addAll(agent.workflow().promptKeys());
            if (agent.workflow().answerPromptKey() != null) {
                keys.add(agent.workflow().answerPromptKey());
            }
            return keys;
        }).orElse(java.util.Set.of());
    }

    /** 追加包的当前 release（items merge 进 merged；identity/spec 拼装）。 */
    private void appendRelease(long bundleId, Map<String, Long> merged,
                               StringBuilder identity, StringBuilder spec, boolean[] hasAny) {
        PromptBundleReleaseEntity latest = releaseMapper.selectOne(
                new LambdaQueryWrapper<PromptBundleReleaseEntity>()
                        .eq(PromptBundleReleaseEntity::getBundleId, bundleId)
                        .orderByDesc(PromptBundleReleaseEntity::getReleaseNo)
                        .last("LIMIT 1"));
        PromptBundleEntity bundle = bundleMapper.selectById(bundleId);
        String name = bundle != null ? bundle.getName() : ("bundle-" + bundleId);
        if (latest == null) {
            log.warn("[Prompt绑定] 包 {} 无任何 release，跳过", name);
            if (identity.length() > 0) {
                identity.append(" + ");
            }
            identity.append(name).append("(无release)");
            return;
        }
        mergeItems(merged, parseItems(latest.getItems()));
        hasAny[0] = true;
        if (identity.length() > 0) {
            identity.append(" + ");
        }
        identity.append(name).append("@r").append(latest.getReleaseNo());
        if (spec.length() > 0) {
            spec.append('|');
        }
        spec.append(bundleId).append(':').append(latest.getReleaseNo());
    }

    private Map<String, Long> parseItems(String itemsJson) {
        try {
            Map<String, Object> raw = objectMapper.readValue(itemsJson,
                    new TypeReference<Map<String, Object>>() {
                    });
            Map<String, Long> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, Long.valueOf(String.valueOf(v))));
            return out;
        } catch (Exception e) {
            log.warn("[Prompt绑定] release items 解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private void mergeItems(Map<String, Long> target, Map<String, Long> source) {
        target.putAll(source); // 同 key 后写胜出（特化覆盖基座）
    }

    /** {key: versionId} → {key: content}（版本查不到记 warn 跳过——装配校验兜底报缺）。 */
    private Map<String, String> resolveContents(Map<String, Long> merged) {
        Map<String, String> contents = new HashMap<>();
        for (Map.Entry<String, Long> e : merged.entrySet()) {
            PromptVersionEntity v = versionMapper.selectById(e.getValue());
            if (v != null) {
                contents.put(e.getKey(), v.getContent());
            } else {
                log.warn("[Prompt绑定] release 引用的版本不存在: {} → version {}", e.getKey(), e.getValue());
            }
        }
        return contents;
    }

    /** 装配校验：requiredKeys ⊆ contents，缺即抛错列出（绝不静默 fallback——行为透明红线）。 */
    private void requireKeysCovered(String agentType, Map<String, String> contents) {
        List<String> missing = requiredKeys(agentType).stream()
                .filter(k -> !contents.containsKey(k))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Prompt 绑定未覆盖骨架必需 key（agentType=" + agentType
                    + "，缺: " + missing + "）——请补齐包内容或回退基线");
        }
    }
}
