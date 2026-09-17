package com.jjx.customer.platform.prompt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.prompt.entity.PromptBundleReleaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Prompt 能力包发布版本 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface PromptBundleReleaseMapper extends BaseMapper<PromptBundleReleaseEntity> {

    /** 取包的最大发布号（无发布返回 null，发布 = max+1）。 */
    @Select("SELECT MAX(release_no) FROM sa_prompt_bundle_release WHERE bundle_id = #{bundleId}")
    Integer maxReleaseNo(Long bundleId);
}
