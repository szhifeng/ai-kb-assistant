package com.fox.aikbassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    Map<String, ChatClient> chatClients(@Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
                                        @Qualifier("openAiChatModel") ChatModel openAiChatModel,
                                        ChatMemory chatMemory) {
        return Map.of(
                "deepseek", buildClient(deepSeekChatModel, chatMemory),
                "openai", buildClient(openAiChatModel, chatMemory));
    }

    /**
     * 不再挂 QuestionAnswerAdvisor：检索由 RagChatService 统一执行一次，
     * 检索结果同时用于注入上下文与生成引用，避免重复检索导致的费用与不一致。
     */
    private static ChatClient buildClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}