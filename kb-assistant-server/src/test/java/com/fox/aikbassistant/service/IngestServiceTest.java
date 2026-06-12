package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.IngestResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IngestServiceTest {

    @Test
    void ingest_splitsAndStoresWithSourceMetadata() {
        VectorStore store = mock(VectorStore.class);
        List<Document> captured = new ArrayList<>();
        doAnswer(inv -> { captured.addAll(inv.getArgument(0)); return null; })
                .when(store).add(anyList());

        IngestService service = new IngestService(store);
        Resource res = new ByteArrayResource(
                "# Title\n\nHello world. ".repeat(200).getBytes()) {
            @Override public String getFilename() { return "note.md"; }
        };

        IngestResult result = service.ingest(res, "note.md");

        assertThat(result.source()).isEqualTo("note.md");
        assertThat(result.chunkCount()).isGreaterThan(0);
        assertThat(captured).isNotEmpty();
        assertThat(captured.get(0).getMetadata()).containsKey("source");
        assertThat(captured.get(0).getMetadata().get("source")).isEqualTo("note.md");
    }
}
