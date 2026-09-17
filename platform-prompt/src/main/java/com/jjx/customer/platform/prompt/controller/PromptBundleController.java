package com.jjx.customer.platform.prompt.controller;

import com.jjx.customer.platform.prompt.entity.PromptBindingEntity;
import com.jjx.customer.platform.prompt.entity.PromptBundleEntity;
import com.jjx.customer.platform.prompt.service.PromptBundleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt 能力包管理 API（P2：console 组包/发布/绑定后端）。
 *
 * <p>工作流：建包（或 fork 现有包）→ 组包发布 release（挑各 key 的版本号快照）→
 * 绑定骨架（base+overlay，切换即时校验覆盖度）。绑定是活引用：基座发新 release 全骨架自动生效；
 * 会话/eval 粘具体 release（不可变快照）保可复现。
 */
@RestController
@RequestMapping("/prompt-bundle")
@RequiredArgsConstructor
public class PromptBundleController {

    private final PromptBundleService bundleService;

    /** 包列表。 */
    @GetMapping("/list")
    public List<PromptBundleService.BundleView> list() {
        return bundleService.listBundles();
    }

    /** 建包。body: {name, description, agentType}。 */
    @PostMapping("/create")
    public PromptBundleEntity create(@RequestBody CreateBundleRequest req) {
        return bundleService.createBundle(req.name(), req.description(), req.agentType());
    }

    /** fork 包（拷最新 release 的 items）。body: {name}。 */
    @PostMapping("/{bundleId}/fork")
    public PromptBundleEntity fork(@PathVariable Long bundleId, @RequestBody CreateBundleRequest req) {
        return bundleService.forkBundle(bundleId, req.name());
    }

    /** release 历史（新→旧，items 为 {key: versionNo}，供前端展示包里装了什么）。 */
    @GetMapping("/{bundleId}/releases")
    public List<PromptBundleService.ReleaseView> releases(@PathVariable Long bundleId) {
        return bundleService.releaseViews(bundleId);
    }

    /** 删除包（连同全部 release；仍被绑定时拒绝并列出骨架）。 */
    @DeleteMapping("/{bundleId}")
    public Map<String, Object> delete(@PathVariable Long bundleId) {
        bundleService.deleteBundle(bundleId);
        return Map.of("ok", true);
    }

    /** 发布 release。body: {items: {key: versionNo}, changeNote}。 */
    @PostMapping("/{bundleId}/releases")
    public Map<String, Object> publish(@PathVariable Long bundleId, @RequestBody PublishRequest req) {
        return Map.of("releaseNo", bundleService.publishRelease(bundleId, req.items(), req.changeNote()));
    }

    /** 绑定切换（切换时即时校验 requiredKeys 覆盖）。body: {agentType, baseBundleId, overlayBundleId}。 */
    @PostMapping("/bind")
    public Map<String, Object> bind(@RequestBody BindRequest req) {
        bundleService.bind(req.agentType(), req.baseBundleId(), req.overlayBundleId());
        return Map.of("ok", true);
    }

    /** 解绑（回基线）。 */
    @PostMapping("/unbind")
    public Map<String, Object> unbind(@RequestBody BindRequest req) {
        bundleService.unbind(req.agentType());
        return Map.of("ok", true);
    }

    /** 全部绑定视图。 */
    @GetMapping("/bindings")
    public List<PromptBindingEntity> bindings() {
        return bundleService.bindings();
    }

    record CreateBundleRequest(String name, String description, String agentType) {
    }

    record PublishRequest(Map<String, Integer> items, String changeNote) {
    }

    record BindRequest(String agentType, Long baseBundleId, Long overlayBundleId) {
    }
}
