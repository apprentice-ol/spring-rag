package com.jjx.customer.platform.prompt.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jjx.customer.platform.common.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 能力包发布版本实体，映射 sa_prompt_bundle_release：发布即不可变快照
 * （items = {prompt_key: version_id} 全量），改包 = 发新 release。
 */
@Data
@TableName(value = "sa_prompt_bundle_release", autoResultMap = true)
public class PromptBundleReleaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bundleId;

    private Integer releaseNo;

    /** {prompt_key: version_id} 全量快照（jsonb，存 JSON 文本） */
    @TableField(value = "items", typeHandler = JsonbTypeHandler.class)
    private String items;

    private String changeNote;

    private LocalDateTime createTime;
}
