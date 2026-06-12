package com.fox.aikbassistant.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.service.RagChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestParam String question) {
        Flux<ServerSentEvent<String>> tokens = ragChatService.stream(question)
                .map(token -> ServerSentEvent.<String>builder().event("token").data(token).build());

        Mono<ServerSentEvent<String>> citationFrame = Mono.fromCallable(() ->
                ServerSentEvent.<String>builder()
                        .event("citations")
                        .data(writeCitations(ragChatService.retrieveCitations(question)))
                        .build());

        return tokens.concatWith(citationFrame);
    }

    private String writeCitations(List<Citation> citations) throws JsonProcessingException {
        return objectMapper.writeValueAsString(citations);
    }
}
