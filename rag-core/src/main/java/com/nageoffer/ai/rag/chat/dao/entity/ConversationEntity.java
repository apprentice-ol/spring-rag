package com.nageoffer.ai.rag.chat.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("sa_conversation")
public class ConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String conversationId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
