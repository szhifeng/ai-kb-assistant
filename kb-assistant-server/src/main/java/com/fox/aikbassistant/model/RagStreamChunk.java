package com.fox.aikbassistant.model;

/**
 * 流式问答片段。type 对应 SSE event 名称，如 reasoning 或 token。
 */
public record RagStreamChunk(String type, String text) {}
