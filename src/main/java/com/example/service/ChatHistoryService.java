package com.example.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.RequiredArgsConstructor;

/**
 * 记忆查看服务：读当前记忆、清空记忆，不参与提问 —— 这边管"记忆里存了什么"，
 * 提问那条路走 {@link AIChatService}（声明式 AI 服务，拿模型和记忆都是它自己的事）。
 */
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    /** 容器里有三个 ChatMemory，msgWindowsDB 是 @Primary，按类型注入拿到的就是它 */
    @Qualifier("msgWindowsDB")
    private final ChatMemory chatMemory;

    public List<ChatMessage> history() {
        return chatMemory.messages();
    }

    public void clear() {
        chatMemory.clear();
    }
}
