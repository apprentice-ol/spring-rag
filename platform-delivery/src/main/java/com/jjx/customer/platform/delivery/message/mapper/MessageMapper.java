package com.jjx.customer.platform.delivery.message.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.delivery.message.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
