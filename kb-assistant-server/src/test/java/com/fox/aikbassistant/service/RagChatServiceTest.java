package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.service.impl.RagChatServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagChatServiceTest {

    @Test
    void toCitations_extractsSourceAndSnippet() {
        Document doc = Document.builder()
                .text("Spring AI 提供 RAG 能力，支持向量检索。")
                .metadata(Map.of("source", "ai.md"))
                .build();

        List<Citation> citations = RagChatServiceImpl.toCitations(List.of(doc));

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).source()).isEqualTo("ai.md");
        assertThat(citations.get(0).snippet()).contains("Spring AI");
    }

    @Test
    void toCitations_usesUnknownWhenSourceMissing() {
        Document doc = Document.builder()
                .text("无来源元数据的内容。")
                .build();

        List<Citation> citations = RagChatServiceImpl.toCitations(List.of(doc));

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).source()).isEqualTo("unknown");
    }
}
