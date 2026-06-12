package com.fox.aikbassistant.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChatClientRouter {

    private static final String DEFAULT_MODEL = "deepseek";

    private final Map<String, ChatClient> clients;

    public ChatClientRouter(@Qualifier("chatClients") Map<String, ChatClient> chatClients) {
        this.clients = chatClients;
    }

    public ChatClient resolve(String model) {
        String key = model == null || model.isBlank() ? DEFAULT_MODEL : model.toLowerCase();
        return clients.getOrDefault(key, clients.get(DEFAULT_MODEL));
    }
}
