package com.jjx.customer.platform.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 资产实体，映射 sa_prompt：逻辑 key 与内容版本分离（版本历史在 sa_prompt_version）。
 */
@Data
@TableName("sa_prompt")
public class PromptEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑 key（classpath 相对路径，如 agent/ops/resolve） */
    private String promptKey;

    /** 当前生效版本（sa_prompt_version.id） */
    private Long currentVersionId;

    /** 用途说明 */
    private String description;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
