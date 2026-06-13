package com.fox.aikbassistant.service.impl;

import com.fox.aikbassistant.config.ChatClientRouter;
import com.fox.aikbassistant.model.ChatAnswer;
import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.model.RagStreamChunk;
import com.fox.aikbassistant.model.RagStreamResult;
import com.fox.aikbassistant.ratelimit.RateLimitExceededException;
import com.fox.aikbassistant.ratelimit.TokenRateLimiter;
import com.fox.aikbassistant.service.RagChatService;
import com.fox.aikbassistant.tool.WebSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagChatServiceImpl implements RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatServiceImpl.class);

    private static final int TOP_K = 4;  //    默认只返回匹配的前4条

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
        log.info("RAG stream start conversationId={}, model={}, webSearchEnabled={}, questionChars={}", conversationId, model, webSearchEnabled, safeLength(question));
        acquireQuota(question, conversationId);
        List<Document> docs = retrieve(question);
        log.info("RAG stream retrieved conversationId={}, docs={}", conversationId, docs.size());
        ChatClient.ChatClientRequestSpec request = basePrompt(question, conversationId, model, docs, webSearchEnabled);
        if (webSearchEnabled) {
            request.tools(webSearchTool);
        }
        Flux<RagStreamChunk> chunks = request.stream().chatResponse()
                .doOnNext(RagChatServiceImpl::logStreamResponse)
                .flatMapIterable(RagChatServiceImpl::toStreamChunks)
                .doOnNext(chunk -> log.debug("RAG stream chunk type={}, chars={}", chunk.type(), safeLength(chunk.text())))
                .doOnComplete(() -> log.info("RAG stream complete conversationId={}", conversationId))
                .doOnError(ex -> log.warn("RAG stream failed conversationId={}, message={}", conversationId, ex.getMessage(), ex));
        return new RagStreamResult(chunks, toCitations(docs), conversationId);
    }

    @Override
    public ChatAnswer call(String question, String conversationId, String model) {
        return call(question, conversationId, model, false);
    }

    @Override
    public ChatAnswer call(String question, String conversationId, String model, boolean webSearchEnabled) {
        log.info("RAG call start conversationId={}, model={}, webSearchEnabled={}, questionChars={}",
                conversationId, model, webSearchEnabled, safeLength(question));
        acquireQuota(question, conversationId);
        List<Document> docs = retrieve(question);
        log.info("RAG call retrieved conversationId={}, docs={}", conversationId, docs.size());
        ChatClient.ChatClientRequestSpec request = basePrompt(question, conversationId, model, docs, webSearchEnabled);
        if (webSearchEnabled) {
            request.tools(webSearchTool);
        }
        ChatResponse response = request.call().chatResponse();
        AssistantMessage output = response == null || response.getResult() == null ? null : response.getResult().getOutput();
        String answer = output == null ? "" : output.getText();
        String reasoning = output == null ? "" : reasoningText(output);
        log.info("RAG call complete conversationId={}, reasoningChars={}, answerChars={}, metadataKeys={}",
                conversationId, safeLength(reasoning), safeLength(answer), output == null ? List.of() : output.getMetadata().keySet());
        return new ChatAnswer(answer, reasoning, toCitations(docs), conversationId);
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

    public static List<RagStreamChunk> toStreamChunks(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        AssistantMessage output = response.getResult().getOutput();
        String reasoning = reasoningText(output);
        String answer = output.getText();
        if ((reasoning == null || reasoning.isEmpty()) && (answer == null || answer.isEmpty())) {
            return List.of();
        }
        if (reasoning == null || reasoning.isEmpty()) {
            return List.of(new RagStreamChunk("token", answer));
        }
        if (answer == null || answer.isEmpty()) {
            return List.of(new RagStreamChunk("reasoning", reasoning));
        }
        return List.of(new RagStreamChunk("reasoning", reasoning), new RagStreamChunk("token", answer));
    }

    private static String reasoningText(AssistantMessage message) {
        return firstMetadataText(message, "reasoningContent", "reasoning_content", "reasoning");
    }

    private static String firstMetadataText(AssistantMessage message, String... keys) {
        for (String key : keys) {
            String value = metadataText(message, key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String metadataText(AssistantMessage message, String key) {
        Object value = message.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private static void logStreamResponse(ChatResponse response) {
        if (!log.isDebugEnabled() || response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = response.getResult().getOutput();
        String reasoning = reasoningText(output);
        String answer = output.getText();
        log.debug("RAG stream response metadataKeys={}, reasoningChars={}, answerChars={}",
                output.getMetadata().keySet(), safeLength(reasoning), safeLength(answer));
    }

    private static int safeLength(String text) {
        return text == null ? 0 : text.length();
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
