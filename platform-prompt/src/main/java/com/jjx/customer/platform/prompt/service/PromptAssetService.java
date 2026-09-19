package com.jjx.customer.platform.prompt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jjx.customer.platform.prompt.entity.PromptEntity;
import com.jjx.customer.platform.prompt.entity.PromptVersionEntity;
import com.jjx.customer.platform.prompt.mapper.PromptMapper;
import com.jjx.customer.platform.prompt.mapper.PromptVersionMapper;
import com.jjx.customer.platform.common.exception.ClientException;
import com.jjx.customer.platform.config.prompt.PromptStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 资产服务（P1：资产化管理面）。
 *
 * <p><b>基线导入</b>（启动）：classpath {@code prompts/**} 为首次种子——缺 key 建档发 v1；
 * 已有 key 时 <b>DB 为运行态真相</b>，classpath 内容与 DB 当前版本不一致只记差异清单（warn + 接口），
 * 不自动建版本（防开发改码被静默压制后无感知，一键"以代码内容发新版本"显式收编）。</p>
 *
 * <p><b>运行链路零变化</b>（P1 验收硬约束）：PromptStore.raw 仍读 classpath，
 * DB 是管理面与将来的 bundle 装配基座（P2 才切运行链路）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAssetService {

    private final PromptMapper promptMapper;
    private final PromptVersionMapper versionMapper;
    private final PromptStore promptStore;

    /** key 全集视图（含当前版本号 + 代码差异标记 + 未建档/线上创建标记 + 链路/环节归类）。 */
    public record PromptKeyView(String promptKey, Integer currentVersion,
                                boolean driftedFromCode, boolean codeOnly, boolean codeMissing,
                                String chain, String chainLabel, String segment, String segmentLabel,
                                LocalDateTime updateTime) {
    }

    /** 启动基线导入：缺 key 建档 v1；已有 key 差异记 warn（不自动建版本）。 */
    @PostConstruct
    void importBaseline() {
        int created = 0;
        List<String> drift = new ArrayList<>();
        for (String key : promptStore.allKeys()) {
            String codeContent = promptStore.raw(key);
            PromptEntity existing = findByKey(key);
            if (existing == null) {
                createVersionInternal(key, codeContent, "基线导入（classpath 种子）");
                created++;
            } else {
                PromptVersionEntity current = versionMapper.selectById(existing.getCurrentVersionId());
                if (current == null || !current.getContent().equals(codeContent)) {
                    drift.add(key);
                }
            }
        }
        log.info("[Prompt资产] 基线导入完成: 新建 {} 个 prompt，代码与线上差异 {} 个{}",
                created, drift.size(), drift.isEmpty() ? "" : "（" + drift + "，可用 /prompt/{key}/sync-from-code 收编）");
    }

    /** key 列表（含当前版本号与代码差异标记，console 首屏）。 */
    public List<PromptKeyView> listKeys() {
        List<PromptKeyView> out = new ArrayList<>();
        for (PromptEntity p : promptMapper.selectList(null)) {
            Integer currentNo = null;
            boolean drifted = false;
            String code = codeContent(p.getPromptKey()); // null = 线上创建（无 classpath 文件）
            if (p.getCurrentVersionId() != null) {
                PromptVersionEntity v = versionMapper.selectById(p.getCurrentVersionId());
                if (v != null) {
                    currentNo = v.getVersionNo();
                    drifted = code != null && !v.getContent().equals(code);
                }
            }
            KeyClass kc = classify(p.getPromptKey());
            out.add(new PromptKeyView(p.getPromptKey(), currentNo, drifted, false, code == null,
                    kc.chain(), kc.chainLabel(), kc.segment(), kc.segmentLabel(), p.getUpdateTime()));
        }
        // DB 缺失但代码新增的 key（导入后新加文件未重启）也标出来（codeOnly：重启或收编即建档）
        for (String key : promptStore.allKeys()) {
            if (findByKey(key) == null) {
                KeyClass kc = classify(key);
                out.add(new PromptKeyView(key, null, true, true, false,
                        kc.chain(), kc.chainLabel(), kc.segment(), kc.segmentLabel(), null));
            }
        }
        return out;
    }

    /** 版本时间线（新→旧）。 */
    public List<PromptVersionEntity> versions(String promptKey) {
        PromptEntity p = requireKey(promptKey);
        return versionMapper.selectList(new LambdaQueryWrapper<PromptVersionEntity>()
                .eq(PromptVersionEntity::getPromptId, p.getId())
                .orderByDesc(PromptVersionEntity::getVersionNo));
    }

    /** 取版本内容（回滚/编辑的预填来源）。 */
    public PromptVersionEntity version(String promptKey, int versionNo) {
        PromptEntity p = requireKey(promptKey);
        PromptVersionEntity v = versionMapper.selectOne(new LambdaQueryWrapper<PromptVersionEntity>()
                .eq(PromptVersionEntity::getPromptId, p.getId())
                .eq(PromptVersionEntity::getVersionNo, versionNo));
        if (v == null) {
            throw new ClientException("版本不存在: " + promptKey + " v" + versionNo);
        }
        return v;
    }

    /**
     * 发新版本（内容不可变语义：只增新行，current 指针前移）。
     * <p>未建档 key 直接建档发 v1（线上新建 prompt 入口——key 只需符合命名规范，
     * 不再要求 classpath 已存在；线上创建的 key 无代码文件，版本即唯一真相）。</p>
     *
     * @return 新版本号
     */
    @Transactional
    public int createVersion(String promptKey, String content, String changeNote) {
        if (!StringUtils.hasText(content)) {
            throw new ClientException("prompt 内容不能为空");
        }
        if (!KEY_PATTERN.matcher(promptKey).matches()) {
            throw new ClientException("key 命名不合法（小写字母/数字与 /_- ，两段以上为宜）: " + promptKey);
        }
        PromptEntity p = findByKey(promptKey);
        if (p == null) {
            return createVersionInternal(promptKey, content, changeNote);
        }
        return appendVersion(p, content, changeNote);
    }

    /** 回滚 = 以旧版本内容发新版本（历史只增不改的语义不变，时间线上可追溯）。 */
    @Transactional
    public int rollback(String promptKey, int toVersionNo) {
        PromptVersionEntity old = version(promptKey, toVersionNo);
        return createVersion(promptKey, old.getContent(), "回滚至 v" + toVersionNo);
    }

    /** 一键收编：以 classpath 当前内容发新版本（消除"代码与线上差异"标记）。线上创建的 key 无代码可收编。 */
    @Transactional
    public int syncFromCode(String promptKey) {
        String code = codeContent(promptKey);
        if (code == null) {
            throw new ClientException("该 key 无 classpath 代码内容（线上创建），无需收编");
        }
        return createVersion(promptKey, code, "以代码内容发新版本（收编开发改动）");
    }

    /** 代码与线上差异清单（排障第一入口：哪些 prompt 的代码改动还没进版本历史）。 */
    public List<String> codeDrift() {
        return listKeys().stream().filter(PromptKeyView::driftedFromCode).map(PromptKeyView::promptKey).toList();
    }

    // ==================== 内部 ====================

    /**
     * key → (链路, 环节) 归类表：资产的组织元数据（console 左栏「链路 → 环节 → key」树）。
     * <p>前缀以 / 结尾 = 前缀匹配，否则精确匹配；段内<b>先长后短</b>（agent/ops/ 先于 agent/）。
     * 未匹配的 key 落「其他」链路。key 空间是设计出来的命名约定，此表集中声明，新增域在此登记。</p>
     */
    private static final List<ChainDef> CHAIN_DEFS = List.of(
            new ChainDef("rag", "RAG 查询链", List.of(
                    new SegmentDef("rag/intent/", "意图识别"),
                    new SegmentDef("rag/query-rewrite", "查询改写"),
                    new SegmentDef("rag/query-expand", "查询拓展"),
                    new SegmentDef("rag/spell-fix", "拼写纠正"),
                    new SegmentDef("rag/pipeline/chitchat-system", "闲聊兜底"),
                    new SegmentDef("rag/pipeline/", "检索回答"))),
            new ChainDef("eval", "评测", List.of(
                    new SegmentDef("eval/", "评测 prompt"))),
            new ChainDef("agent", "Agent 编排", List.of(
                    new SegmentDef("agent/ops/", "运维诊断"),
                    new SegmentDef("workflow/ops_diagnose_v2/", "运维诊断"),
                    new SegmentDef("agent/", "公共规范"))),
            new ChainDef("ingestion", "文档入库", List.of(
                    new SegmentDef("ingestion/enhancer/", "增强 Enhancer"),
                    new SegmentDef("ingestion/enricher/", "富化 Enricher"),
                    new SegmentDef("ingestion/pdf-format-guard", "格式守卫"))),
            new ChainDef("vlm", "多模态", List.of(
                    new SegmentDef("vlm/", "视觉理解"))));

    private record SegmentDef(String prefix, String label) {
        boolean matches(String key) {
            return prefix.endsWith("/") ? key.startsWith(prefix) : key.equals(prefix);
        }
    }

    private record ChainDef(String code, String label, List<SegmentDef> segments) {
    }

    private record KeyClass(String chain, String chainLabel, String segment, String segmentLabel) {

        static final KeyClass OTHER = new KeyClass("other", "其他", null, null);
    }

    private static KeyClass classify(String key) {
        for (ChainDef c : CHAIN_DEFS) {
            for (SegmentDef s : c.segments()) {
                if (s.matches(key)) {
                    return new KeyClass(c.code(), c.label(), s.prefix(), s.label());
                }
            }
        }
        return KeyClass.OTHER;
    }

    /** key 命名规范（与现有 key 空间一致：小写段 + / 分层，如 agent/ops/resolve） */
    private static final java.util.regex.Pattern KEY_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9/_-]{1,199}$");

    /** classpath 代码内容；key 不在代码里返回 null（线上创建）。raw 对未知 key 会抛异常，先按 allKeys 过滤。 */
    private String codeContent(String key) {
        if (!promptStore.allKeys().contains(key)) {
            return null;
        }
        try {
            return promptStore.raw(key);
        } catch (Exception e) {
            return null;
        }
    }

    private PromptEntity findByKey(String promptKey) {
        return promptMapper.selectOne(new LambdaQueryWrapper<PromptEntity>()
                .eq(PromptEntity::getPromptKey, promptKey));
    }

    private PromptEntity requireKey(String promptKey) {
        PromptEntity p = findByKey(promptKey);
        if (p == null) {
            throw new ClientException("未知 prompt key: " + promptKey);
        }
        return p;
    }

    /** 建档 + v1（事务内：prompt 行 + version 行 + current 指针）。 */
    @Transactional
    int createVersionInternal(String promptKey, String content, String changeNote) {
        PromptEntity p = new PromptEntity();
        p.setPromptKey(promptKey);
        p.setDescription(null);
        promptMapper.insert(p);
        return appendVersion(p, content, changeNote);
    }

    private int appendVersion(PromptEntity p, String content, String changeNote) {
        Integer max = versionMapper.maxVersionNo(p.getId());
        int nextNo = (max == null ? 0 : max) + 1;

        PromptVersionEntity v = new PromptVersionEntity();
        v.setPromptId(p.getId());
        v.setVersionNo(nextNo);
        v.setContent(content);
        v.setChangeNote(StringUtils.hasText(changeNote) ? changeNote : "");
        versionMapper.insert(v);

        PromptEntity upd = new PromptEntity();
        upd.setId(p.getId());
        upd.setCurrentVersionId(v.getId());
        upd.setUpdateTime(LocalDateTime.now());
        promptMapper.updateById(upd);
        return nextNo;
    }
}
