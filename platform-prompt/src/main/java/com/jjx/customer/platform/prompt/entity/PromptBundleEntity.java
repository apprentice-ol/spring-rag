package com.jjx.customer.platform.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 能力包实体，映射 sa_prompt_bundle：基座/特化包的组包主体（内容在 release 快照）。
 */
@Data
@TableName("sa_prompt_bundle")
public class PromptBundleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 包名（如 ops-standard / kb-finance / platform-base） */
    private String name;

    private String description;

    /** 限定适用骨架（null = 通用包，可作任意绑定的基座） */
    private String agentType;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
