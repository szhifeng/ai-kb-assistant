package com.fox.aikbassistant.service.impl;

import com.fox.aikbassistant.config.ChatClientRouter;
import com.fox.aikbassistant.model.ChatAnswer;
import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.model.RagStreamResult;
import com.fox.aikbassistant.ratelimit.RateLimitExceededException;
import com.fox.aikbassistant.ratelimit.TokenRateLimiter;
import com.fox.aikbassistant.service.RagChatService;
import com.fox.aikbassistant.tool.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagChatServiceImpl implements RagChatService {

    private static final int TOP_K = 4;

    private static final String SYSTEM_PROMPT = """
            你是知识库问答助手。请严格根据下面提供的【知识库上下文】回答用户问题。
            若上下文不足以回答，请明确说明无法从知识库得到答案，不要编造，也不要使用外部实时信息。

            【知识库上下文】
            {context}
            """;

    private static final String WEB_SEARCH_SYSTEM_PROMPT = """
            你是知识库问答助手。请优先根据下面提供的【知识库上下文】回答用户问题。
            若知识库上下文不足，可调用联网搜索工具补充实时信息；回答中需要区分哪些结论来自知识库，哪些来自联网信息。
            不要编造，无法确认时请明确说明。

            【知识库上下文】
            {context}
            """;

    private final ChatClientRouter chatClientRouter;
    private final VectorStore vectorStore;
    private final TokenRateLimiter rateLimiter;
    private final WebSearchTool webSearchTool;

    public RagChatServiceImpl(ChatClientRouter chatClientRouter,
                              VectorStore vectorStore,
                              TokenRateLimiter rateLimiter,
                              WebSearchTool webSearchTool) {
        this.chatClientRouter = chatClientRouter;
        this.vectorStore = vectorStore;
        this.rateLimiter = rateLimiter;
        this.webSearchTool = webSearchTool;
    }

    @Override
    public RagStreamResult stream(String question) {
        return stream(question, "default");
    }

    @Override
    public RagStreamResult stream(String question, String conversationId) {
        return stream(question, conversationId, "deepseek");
    }

    @Override
    public RagStreamResult stream(String question, String conversationId, String model) {
        return stream(question, conversationId, model, false);
    }

    @Override
    public RagStreamResult stream(String question, String conversationId, String model, boolean webSearchEnabled) {
        acquireQuota(question, conversationId);
        List<Document> docs = retrieve(question);
        ChatClient.ChatClientRequestSpec request = basePrompt(question, conversationId, model, docs, webSearchEnabled);
        if (webSearchEnabled) {
            request.tools(webSearchTool);
        }
        Flux<String> tokens = request.stream().content();
        return new RagStreamResult(tokens, toCitations(docs), conversationId);
    }

    @Override
    public ChatAnswer call(String question, String conversationId, String model) {
        return call(question, conversationId, model, false);
    }

    @Override
    public ChatAnswer call(String question, String conversationId, String model, boolean webSearchEnabled) {
        acquireQuota(question, conversationId);
        List<Document> docs = retrieve(question);
        ChatClient.ChatClientRequestSpec request = basePrompt(question, conversationId, model, docs, webSearchEnabled);
        if (webSearchEnabled) {
            request.tools(webSearchTool);
        }
        String answer = request.call().content();
        return new ChatAnswer(answer, toCitations(docs), conversationId);
    }

    private ChatClient.ChatClientRequestSpec basePrompt(String question,
                                                        String conversationId,
                                                        String model,
                                                        List<Document> docs,
                                                        boolean webSearchEnabled) {
        String systemPrompt = webSearchEnabled ? WEB_SEARCH_SYSTEM_PROMPT : SYSTEM_PROMPT;
        return chatClientRouter.resolve(model).prompt()
                .system(s -> s.text(systemPrompt).param("context", toContext(docs)))
                .user(question)
                .toolContext(Map.of("conversationId", conversationId))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));
    }

    private void acquireQuota(String question, String conversationId) {
        long estimatedTokens = estimateTokens(question);
        if (!rateLimiter.tryAcquire(conversationId, estimatedTokens)) {
            throw new RateLimitExceededException("token 配额超限");
        }
        rateLimiter.record(conversationId, estimatedTokens);
    }

    private List<Document> retrieve(String question) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(TOP_K).build());
    }

    public static String toContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（无相关知识库内容）";
        }
        return docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));
    }

    public static List<Citation> toCitations(List<Document> docs) {
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
