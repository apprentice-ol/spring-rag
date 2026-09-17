package com.jjx.customer.platform.prompt.controller;

import com.jjx.customer.platform.prompt.entity.PromptVersionEntity;
import com.jjx.customer.platform.prompt.service.PromptAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Prompt 资产管理 API（P1：console 面板后端）。
 *
 * <p>能力：key 列表（含代码差异标记）/ 版本时间线 / 版本内容 / 双版本 diff 预取 /
 * 发新版本 / 回滚（=以旧版本内容发新版本）/ 以代码内容收编 / 代码差异清单。
 * <p>版本不可变：所有写操作只增新行，current 指针前移——历史永不丢失。
 * <p>key 经 query param 传递（promptKey 是含斜杠的路径形式，@PathVariable 无法匹配；
 * 编码斜杠又被容器默认拒绝）。
 */
@RestController
@RequestMapping("/prompt")
@RequiredArgsConstructor
public class PromptController {

    private final PromptAssetService promptAssetService;

    /** key 列表（含当前版本号 + 是否与 classpath 代码有差异）。 */
    @GetMapping("/keys")
    public List<PromptAssetService.PromptKeyView> keys() {
        return promptAssetService.listKeys();
    }

    /** 代码与线上差异清单（开发改码未收编的 prompt）。 */
    @GetMapping("/code-drift")
    public Map<String, Object> codeDrift() {
        return Map.of("driftedKeys", promptAssetService.codeDrift());
    }

    /** 版本时间线（新→旧）。 */
    @GetMapping("/versions")
    public List<PromptVersionEntity> versions(@RequestParam String key) {
        return promptAssetService.versions(key);
    }

    /** 版本内容（回滚/编辑预填与双栏 diff 预取）。no 缺省 = 当前版本。 */
    @GetMapping("/version")
    public PromptVersionEntity version(@RequestParam String key,
                                       @RequestParam(required = false) Integer no) {
        if (no == null) {
            List<PromptVersionEntity> versions = promptAssetService.versions(key);
            if (versions.isEmpty()) {
                throw new IllegalArgumentException("该 prompt 暂无版本: " + key);
            }
            return versions.get(0);
        }
        return promptAssetService.version(key, no);
    }

    /** 双版本 diff 预取（一次拿两份内容，逐行 diff 由前端渲染）。 */
    @GetMapping("/diff")
    public Map<String, Object> diff(@RequestParam String key,
                                    @RequestParam int from, @RequestParam int to) {
        return Map.of(
                "from", promptAssetService.version(key, from),
                "to", promptAssetService.version(key, to));
    }

    /** 发新版本。body: {content, changeNote}。 */
    @PostMapping("/versions")
    public Map<String, Object> createVersion(@RequestParam String key,
                                             @RequestBody CreateVersionRequest req) {
        return Map.of("versionNo", promptAssetService.createVersion(key, req.content(), req.changeNote()));
    }

    /** 回滚（以旧版本内容发新版本）。 */
    @PostMapping("/rollback")
    public Map<String, Object> rollback(@RequestParam String key, @RequestParam int to) {
        return Map.of("versionNo", promptAssetService.rollback(key, to));
    }

    /** 以 classpath 代码内容发新版本（收编开发改动，消除差异标记）。 */
    @PostMapping("/sync-from-code")
    public Map<String, Object> syncFromCode(@RequestParam String key) {
        return Map.of("versionNo", promptAssetService.syncFromCode(key));
    }

    record CreateVersionRequest(String content, String changeNote) {
    }
}
