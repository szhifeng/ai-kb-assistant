package com.fox.aikbassistant;

import com.fox.aikbassistant.service.IngestService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 入库链路集成测试，针对本地 docker-compose 启动的 pgvector + redis-stack
 * (localhost:5432 / 6379)。EmbeddingModel 用 mock，避免调用真实云端 API。
 * 需先执行：docker compose -f docker/docker-compose.yml up -d
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestIntegrationTest {

    @Autowired
    IngestService ingestService;
    @Autowired
    VectorStore vectorStore;
    @MockitoBean
    EmbeddingModel embeddingModel;

    @Test
    void ingestThenRetrieve() {
        float[] vec = new float[1536];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = 0.01f;
        }
        when(embeddingModel.dimensions()).thenReturn(1536);
        when(embeddingModel.embed(ArgumentMatchers.<String>any())).thenReturn(vec);
        when(embeddingModel.embed(ArgumentMatchers.<Document>any())).thenReturn(vec);
        // PgVectorStore 走批量 embed(List<Document>, options, batchingStrategy)，逐条返回向量
        when(embeddingModel.embed(ArgumentMatchers.<List<Document>>any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    List<Document> docs = inv.getArgument(0);
                    return docs.stream().map(d -> vec).toList();
                });

        Resource res = new ByteArrayResource(
                "Spring AI RAG 知识库。".repeat(50).getBytes()) {
            @Override
            public String getFilename() {
                return "doc.md";
            }
        };
        ingestService.ingest(res, "doc.md");

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query("Spring AI").topK(3).build());
        assertThat(results).isNotEmpty();
    }
}
