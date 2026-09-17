package com.jjx.customer.platform.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 版本实体，映射 sa_prompt_version：不可变只增不改（发新版本=新行，旧版本永不覆盖）。
 */
@Data
@TableName("sa_prompt_version")
public class PromptVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long promptId;

    /** 版本号（每 prompt 独立自增） */
    private Integer versionNo;

    /** 版本内容（Markdown 全文） */
    private String content;

    /** 变更说明 */
    private String changeNote;

    private LocalDateTime createTime;
}
