package com.jjx.customer.platform.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 绑定实体，映射 sa_prompt_binding：骨架 → (基座包, 特化包) 的活包名引用——
 * 每次装配解析各包「当前 release」合并（基座发新版全骨架自动生效；eval/会话粘具体 release 保可复现）。
 */
@Data
@TableName("sa_prompt_binding")
public class PromptBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 绑定主体（骨架类型，如 ops_diagnose / knowledge） */
    private String agentType;

    /** 基座包（公共区 key；null = 纯基线+特化） */
    private Long baseBundleId;

    /** 特化包（骨架特有 key + 对基座的覆盖；null = 无特化） */
    private Long overlayBundleId;

    private LocalDateTime updateTime;
}
