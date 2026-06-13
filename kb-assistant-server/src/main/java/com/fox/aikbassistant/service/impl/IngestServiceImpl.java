package com.fox.aikbassistant.service.impl;

import com.fox.aikbassistant.model.DocumentDetail;
import com.fox.aikbassistant.model.DocumentInfo;
import com.fox.aikbassistant.model.IngestResult;
import com.fox.aikbassistant.model.UploadAudit;
import com.fox.aikbassistant.service.IngestService;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class IngestServiceImpl implements IngestService {

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().build();

    public IngestServiceImpl(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IngestResult ingest(Resource resource, String source) {
        return ingest(resource, source, 0, null);
    }

    @Override
    public IngestResult ingest(Resource resource, String source, long size, String uploaderEmail) {
        String auditId = UUID.randomUUID().toString();
        try {
            List<Document> docs = read(resource, source);
            List<Document> chunks = splitter.apply(docs);
            chunks.forEach(d -> d.getMetadata().put("source", source));
            vectorStore.add(chunks);
            IngestResult result = new IngestResult(source, chunks.size());
            insertAudit(auditId, uploaderEmail, source, size, "success", "解析成功", chunks.size());
            return result;
        }
        catch (RuntimeException ex) {
            insertAudit(auditId, uploaderEmail, source, size, "failed", ex.getMessage(), null);
            throw ex;
        }
    }

    @Override
    public List<DocumentInfo> listDocuments() {
        return jdbcTemplate.query(
                "SELECT metadata->>'source' AS source, COUNT(*) AS chunk_count "
                        + "FROM vector_store WHERE metadata->>'source' IS NOT NULL "
                        + "GROUP BY metadata->>'source' ORDER BY source",
                (rs, rowNum) -> new DocumentInfo(rs.getString("source"), rs.getLong("chunk_count")));
    }

    @Override
    public List<DocumentDetail> listDocumentDetails() {
        return jdbcTemplate.query("""
                SELECT d.source, d.chunk_count, a.size, a.uploader_email, a.status, a.message, a.created_at
                FROM (
                  SELECT metadata->>'source' AS source, COUNT(*) AS chunk_count
                  FROM vector_store
                  WHERE metadata->>'source' IS NOT NULL
                  GROUP BY metadata->>'source'
                ) d
                LEFT JOIN LATERAL (
                  SELECT size, uploader_email, status, message, created_at
                  FROM document_upload_audit
                  WHERE source = d.source
                  ORDER BY created_at DESC
                  LIMIT 1
                ) a ON true
                ORDER BY d.source
                """, (rs, rowNum) -> new DocumentDetail(
                rs.getString("source"),
                rs.getLong("chunk_count"),
                nullableLong(rs, "size"),
                rs.getString("uploader_email"),
                rs.getString("status"),
                rs.getString("message"),
                toInstant(rs, "created_at")));
    }

    @Override
    public List<UploadAudit> listUploadAudits(String email) {
        String sql = "SELECT id, uploader_email, source, size, status, message, chunk_count, created_at "
                + "FROM document_upload_audit ";
        Object[] args = new Object[] {};
        if (email != null && !email.isBlank()) {
            sql += "WHERE lower(uploader_email) = lower(?) ";
            args = new Object[] { email };
        }
        sql += "ORDER BY created_at DESC LIMIT 100";
        return jdbcTemplate.query(sql, this::mapAudit, args);
    }

    @Override
    public void deleteBySource(String source) {
        Filter.Expression expression = new FilterExpressionBuilder().eq("source", source).build();
        vectorStore.delete(expression);
    }

    private List<Document> read(Resource resource, String source) {
        if (isMarkdownDocument(resource, source)) {
            return new MarkdownDocumentReader(resource,
                    MarkdownDocumentReaderConfig.builder().build()).get();
        }
        return new TikaDocumentReader(resource).get();
    }

    private static boolean isMarkdownDocument(Resource resource, String source) {
        String documentName = source;
        if ((documentName == null || documentName.isBlank()) && resource != null) {
            documentName = resource.getFilename();
        }
        return documentName != null && documentName.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private void insertAudit(String id, String uploaderEmail, String source, long size,
                             String status, String message, Integer chunkCount) {
        String normalizedEmail = normalizeEmail(uploaderEmail);
        if (normalizedEmail != null) {
            ensureUser(normalizedEmail);
        }
        jdbcTemplate.update("""
                INSERT INTO document_upload_audit
                (id, uploader_email, source, size, status, message, chunk_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                """, id, normalizedEmail, source, size, status, message, chunkCount);
    }

    private UploadAudit mapAudit(ResultSet rs, int rowNum) throws SQLException {
        return new UploadAudit(
                rs.getString("id"),
                rs.getString("uploader_email"),
                rs.getString("source"),
                rs.getLong("size"),
                rs.getString("status"),
                rs.getString("message"),
                (Integer) rs.getObject("chunk_count"),
                toInstant(rs, "created_at"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void ensureUser(String email) {
        String displayName = email.substring(0, email.indexOf('@'));
        jdbcTemplate.update("""
                INSERT INTO kb_user_profile (email, display_name, signed_in_at)
                VALUES (?, ?, now())
                ON CONFLICT (email) DO NOTHING
                """, email, displayName);
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }
}
