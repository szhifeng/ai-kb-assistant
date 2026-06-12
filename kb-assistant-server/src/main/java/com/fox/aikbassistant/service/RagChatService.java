package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.Citation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class RagChatService {

    private static final int TOP_K = 4;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagChatService(ChatClient ragChatClient, VectorStore vectorStore) {
        this.chatClient = ragChatClient;
        this.vectorStore = vectorStore;
    }

    public Flux<String> stream(String question) {
        return chatClient.prompt().user(question).stream().content();
    }

    public List<Citation> retrieveCitations(String question) {
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(TOP_K).build());
        return toCitations(docs);
    }

    static List<Citation> toCitations(List<Document> docs) {
        return docs.stream()
                .map(d -> new Citation(
                        String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                        snippet(d.getText()),
                        d.getScore()))
                .toList();
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }
}
