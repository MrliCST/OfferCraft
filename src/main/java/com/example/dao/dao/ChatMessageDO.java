package com.example.dao.dao;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * chat_message 表的数据对象：一行 = 一条消息。
 * 只在这一层内部流转，往上（repository 以上）一律用 langchain4j 的 ChatMessage。
 */
@Data
@TableName("chat_message")
public class ChatMessageDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话标识，对应 langchain4j 的 memoryId */
    private String memoryId;

    /** 消息在会话中的顺序，从 0 开始 */
    private Integer msgSeq;

    /** SYSTEM / USER / AI / TOOL_EXECUTION_RESULT / CUSTOM，冗余出来方便直接看表 */
    private String messageType;

    /** 单条消息的 json，由 ChatMessageSerializer 生成 */
    private String content;

    private LocalDateTime createdAt;
}
