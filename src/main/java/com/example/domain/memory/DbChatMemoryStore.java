package com.example.domain.memory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.dao.dao.ChatMessageDO;
import com.example.dao.repository.ChatMessageRepository;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;

/**
 * 记忆落库的领域实现：把 langchain4j 的 ChatMemoryStore 端口接到数据库上。
 * 消息怎么序列化、窗口怎么覆盖，是记忆领域的决定，所以这一层留在 domain；
 * dao 那边只负责按 memoryId 读写 chat_message 的行。
 */
@Component
@RequiredArgsConstructor
public class DbChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageRepository chatMessageRepository;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return chatMessageRepository.selectByMemoryId(memoryId.toString()).stream()
                .map(row -> ChatMessageDeserializer.messageFromJson(row.getContent()))
                .toList();
    }

    /**
     * 整体覆盖写入。
     * 短期记忆每轮都会重算窗口再回调这里，先删后插比做增量 diff 可靠得多，所以包一个事务。
     */
    @Override
    @Transactional
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryId.toString();

        List<ChatMessageDO> rows = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);

            ChatMessageDO row = new ChatMessageDO();
            row.setMemoryId(id);
            row.setMsgSeq(i);
            row.setMessageType(message.type().name());
            row.setContent(ChatMessageSerializer.messageToJson(message));
            row.setCreatedAt(LocalDateTime.now());

            rows.add(row);
        }

        chatMessageRepository.deleteByMemoryId(id);
        chatMessageRepository.insertBatch(rows);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        chatMessageRepository.deleteByMemoryId(memoryId.toString());
    }
}
