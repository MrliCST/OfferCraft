package com.example.dao.repository;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.dao.dao.ChatMessageDO;
import com.example.dao.mapper.ChatMessageMapper;

import lombok.RequiredArgsConstructor;

/**
 * chat_message 表的仓储，只跟 ChatMessageDO 打交道。
 * 不掺任何业务语义 —— 怎么把消息序列化、会话怎么覆盖，都是领域层决定的事。
 */
@Repository
@RequiredArgsConstructor
public class ChatMessageRepository {

    private final ChatMessageMapper chatMessageMapper;

    /** 按会话标识顺序读出所有行 */
    public List<ChatMessageDO> selectByMemoryId(String memoryId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getMemoryId, memoryId)
                .orderByAsc(ChatMessageDO::getMsgSeq));
    }

    public void deleteByMemoryId(String memoryId) {
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageDO>()
                .eq(ChatMessageDO::getMemoryId, memoryId));
    }

    /** 逐条插入，调用方自己保证事务 */
    public void insertBatch(List<ChatMessageDO> rows) {
        rows.forEach(chatMessageMapper::insert);
    }
}
