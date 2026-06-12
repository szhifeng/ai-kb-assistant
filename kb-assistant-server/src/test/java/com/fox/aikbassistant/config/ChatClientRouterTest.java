package com.fox.aikbassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatClientRouterTest {

    @Test
    void resolvesDefaultWhenUnknown() {
        Map<String, ChatClient> clients = Map.of(
                "deepseek", mock(ChatClient.class),
                "openai", mock(ChatClient.class));
        ChatClientRouter router = new ChatClientRouter(clients);

        assertThat(router.resolve("openai")).isSameAs(clients.get("openai"));
        assertThat(router.resolve("unknown")).isSameAs(clients.get("deepseek"));
        assertThat(router.resolve(null)).isSameAs(clients.get("deepseek"));
    }
}
