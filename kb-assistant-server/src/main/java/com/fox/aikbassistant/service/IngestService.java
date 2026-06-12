package com.fox.aikbassistant.service;

import com.fox.aikbassistant.model.IngestResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IngestService {

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public IngestResult ingest(Resource resource, String source) {
        List<Document> docs = read(resource, source);
        List<Document> chunks = splitter.apply(docs);
        chunks.forEach(d -> d.getMetadata().put("source", source));
        vectorStore.add(chunks);
        return new IngestResult(source, chunks.size());
    }

    private List<Document> read(Resource resource, String source) {
        if (source != null && source.toLowerCase().endsWith(".md")) {
            return new MarkdownDocumentReader(resource,
                    MarkdownDocumentReaderConfig.builder().build()).get();
        }
        return new TikaDocumentReader(resource).get();
    }
}
