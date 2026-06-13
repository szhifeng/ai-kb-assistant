package com.fox.aikbassistant.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fox.aikbassistant.model.ChatMessage;
import com.fox.aikbassistant.model.Citation;
import com.fox.aikbassistant.model.ConversationSession;
import com.fox.aikbassistant.model.UserProfile;
import com.fox.aikbassistant.service.UserWorkspaceService;
import com.fox.aikbassistant.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class UserWorkspaceServiceImpl implements UserWorkspaceService {

    private final JdbcTemplate jdbcTemplate;

    public UserWorkspaceServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserProfile login(String email) {
        String normalized = normalizeEmail(email);
        String displayName = normalized.substring(0, normalized.indexOf('@'));
        jdbcTemplate.update("""
                INSERT INTO kb_user_profile (email, display_name, signed_in_at)
                VALUES (?, ?, now())
                ON CONFLICT (email) DO UPDATE SET signed_in_at = EXCLUDED.signed_in_at
                """, normalized, displayName);
        return getUser(normalized);
    }

    @Override
    public UserProfile getUser(String email) {
        String normalized = normalizeEmail(email);
        List<UserProfile> users = jdbcTemplate.query(
                "SELECT email, display_name, signed_in_at FROM kb_user_profile WHERE email = ?",
                (rs, rowNum) -> new UserProfile(
                        rs.getString("email"),
                        rs.getString("display_name"),
                        toInstant(rs, "signed_in_at")),
                normalized);
        return users.isEmpty() ? login(normalized) : users.get(0);
    }

    @Override
    public List<ConversationSession> listSessions(String email) {
        return jdbcTemplate.query("""
                SELECT s.id, s.title, s.created_at, s.updated_at, COUNT(m.id) AS message_count
                FROM kb_conversation_session s
                LEFT JOIN kb_chat_message m ON m.session_id = s.id AND m.email = s.email
                WHERE s.email = ?
                GROUP BY s.id, s.title, s.created_at, s.updated_at
                ORDER BY s.updated_at DESC
                """, this::mapSession, normalizeEmail(email));
    }

    @Override
    public ConversationSession createSession(String email, String title) {
        String normalized = normalizeEmail(email);
        getUser(normalized);
        String id = "email:" + normalized + ":" + UUID.randomUUID();
        String safeTitle = title == null || title.isBlank() ? "新会话" : title.trim();
        jdbcTemplate.update("""
                INSERT INTO kb_conversation_session (id, email, title, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """, id, normalized, safeTitle);
        return listSessions(normalized).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Override
    public void deleteSession(String email, String sessionId) {
        String normalized = normalizeEmail(email);
        jdbcTemplate.update("DELETE FROM kb_chat_message WHERE email = ? AND session_id = ?", normalized, sessionId);
        jdbcTemplate.update("DELETE FROM kb_conversation_session WHERE email = ? AND id = ?", normalized, sessionId);
    }

    @Override
    public List<ChatMessage> listMessages(String email, String sessionId) {
        return jdbcTemplate.query("""
                SELECT id, role, content, citations_json, created_at, mode, web_search_enabled, error
                FROM kb_chat_message
                WHERE email = ? AND session_id = ?
                ORDER BY created_at ASC
                """, this::mapMessage, normalizeEmail(email), sessionId);
    }

    @Override
    public void appendMessage(String email, String sessionId, ChatMessage message) {
        String normalized = normalizeEmail(email);
        ensureSession(normalized, sessionId);
        jdbcTemplate.update("""
                INSERT INTO kb_chat_message
                (id, email, session_id, role, content, citations_json, created_at, mode, web_search_enabled, error)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                message.id(), normalized, sessionId, message.role(), message.content(), writeCitations(message.citations()),
                Timestamp.from(message.createdAt()), message.mode(), message.webSearchEnabled(), message.error());
        jdbcTemplate.update("UPDATE kb_conversation_session SET updated_at = now() WHERE email = ? AND id = ?", normalized, sessionId);
    }

    private void ensureSession(String email, String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_conversation_session WHERE email = ? AND id = ?",
                Integer.class, email, sessionId);
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO kb_conversation_session (id, email, title, created_at, updated_at)
                    VALUES (?, ?, ?, now(), now())
                    """, sessionId, email, "默认会话");
        }
    }

    private ConversationSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationSession(
                rs.getString("id"),
                rs.getString("title"),
                toInstant(rs, "created_at"),
                toInstant(rs, "updated_at"),
                rs.getLong("message_count"));
    }

    private ChatMessage mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ChatMessage(
                rs.getString("id"),
                rs.getString("role"),
                rs.getString("content"),
                readCitations(rs.getString("citations_json")),
                toInstant(rs, "created_at"),
                rs.getString("mode"),
                rs.getBoolean("web_search_enabled"),
                rs.getString("error"));
    }

    private String writeCitations(List<Citation> citations) {
        return JsonUtils.toJson(citations == null ? List.of() : citations);
    }

    private List<Citation> readCitations(String json) {
        try {
            return JsonUtils.fromJson(json == null ? "[]" : json, new TypeReference<>() {});
        }
        catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
