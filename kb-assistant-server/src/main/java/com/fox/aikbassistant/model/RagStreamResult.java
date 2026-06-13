package com.fox.aikbassistant.model;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 流式问答结果：chunk 流与引用列表来自同一次向量检索，保证上下文与引用一致。
 */
public record RagStreamResult(Flux<RagStreamChunk> chunks, List<Citation> citations, String conversationId) {}
