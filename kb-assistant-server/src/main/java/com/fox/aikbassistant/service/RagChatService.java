package com.fox.aikbassistant.service;

import com.fox.aikbassistant.config.ChatClientRouter;
import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.ratelimit.RateLimitExceededException;
import com.fox.aikbassistant.ratelimit.TokenRateLimiter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
public class RagChatService {

    private static final int TOP_K = 4;

    private final ChatClientRouter chatClientRouter;
    private final VectorStore vectorStore;
    private final TokenRateLimiter rateLimiter;
    private final com.fox.aikbassistant.tool.SqlQueryTool sqlQueryTool;
    private final com.fox.aikbassistant.tool.WebSearchTool webSearchTool;

    public RagChatService(ChatClientRouter chatClientRouter,
                          VectorStore vectorStore,
                          TokenRateLimiter rateLimiter,
                          com.fox.aikbassistant.tool.SqlQueryTool sqlQueryTool,
                          com.fox.aikbassistant.tool.WebSearchTool webSearchTool) {
        this.chatClientRouter = chatClientRouter;
        this.vectorStore = vectorStore;
        this.rateLimiter = rateLimiter;
        this.sqlQueryTool = sqlQueryTool;
        this.webSearchTool = webSearchTool;
    }

    public Flux<String> stream(String question) {
        return stream(question, "default");
    }

    public Flux<String> stream(String question, String conversationId) {
        return stream(question, conversationId, "deepseek");
    }

    public Flux<String> stream(String question, String conversationId, String model) {
        long estimatedTokens = estimateTokens(question);
        if (!rateLimiter.tryAcquire(conversationId, estimatedTokens)) {
            return Flux.error(new RateLimitExceededException("token 配额超限"));
        }
        rateLimiter.record(conversationId, estimatedTokens);
        return chatClientRouter.resolve(model).prompt()
                .user(question)
                .tools(sqlQueryTool, webSearchTool)
                .toolContext(Map.of("conversationId", conversationId))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream().content();
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

    private static long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1000;
        }
        return Math.max(1, text.length() / 4) + 1000;
    }
}
