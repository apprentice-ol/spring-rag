package com.jjx.customer.platform.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.prompt.entity.PromptVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Prompt 版本 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface PromptVersionMapper extends BaseMapper<PromptVersionEntity> {

    /** 取 prompt 的最大版本号（无版本返回 null，发新版本 = max+1）。 */
    @Select("SELECT MAX(version_no) FROM sa_prompt_version WHERE prompt_id = #{promptId}")
    Integer maxVersionNo(Long promptId);
}
