package com.fox.aikbassistant.controller;

import com.fox.aikbassistant.model.ChatAnswer;
import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.model.RagStreamResult;
import com.fox.aikbassistant.service.RagChatService;
import com.fox.aikbassistant.util.JsonUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagChatService ragChatService;

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    public record ChatRequest(String question, String conversationId, String model, Boolean webSearchEnabled) {}

    @PostMapping
    public ChatAnswer chat(@RequestBody ChatRequest request) {
        String conversationId = request.conversationId() == null ? "default" : request.conversationId();
        String model = request.model() == null ? "deepseek" : request.model();
        boolean webSearchEnabled = Boolean.TRUE.equals(request.webSearchEnabled());
        return ragChatService.call(request.question(), conversationId, model, webSearchEnabled);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String question,
                                                @RequestParam(defaultValue = "default") String conversationId,
                                                @RequestParam(defaultValue = "deepseek") String model,
                                                @RequestParam(defaultValue = "false") boolean webSearchEnabled) {
        RagStreamResult result = ragChatService.stream(question, conversationId, model, webSearchEnabled);

        Flux<ServerSentEvent<String>> tokens = result.tokens()
                .map(token -> ServerSentEvent.<String>builder().event("token").data(token).build());

        Mono<ServerSentEvent<String>> citationFrame = Mono.fromCallable(() ->
                ServerSentEvent.<String>builder()
                        .event("citations")
                        .data(writeCitations(result.citations()))
                        .build());

        return tokens.concatWith(citationFrame);
    }

    private String writeCitations(List<Citation> citations) {
        return JsonUtils.toJson(citations);
    }
}
