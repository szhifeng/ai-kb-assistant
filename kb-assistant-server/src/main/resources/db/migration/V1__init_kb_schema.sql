CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS vector_store (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding VECTOR(2048)
);

CREATE INDEX IF NOT EXISTS vector_store_metadata_idx
    ON vector_store USING gin (metadata);

CREATE INDEX IF NOT EXISTS vector_store_source_idx
    ON vector_store ((metadata->>'source'));

CREATE TABLE IF NOT EXISTS kb_user_profile (
    email VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    signed_in_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS kb_conversation_session (
    id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL REFERENCES kb_user_profile(email) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS kb_conversation_session_email_updated_idx
    ON kb_conversation_session (email, updated_at DESC);

CREATE TABLE IF NOT EXISTS kb_chat_message (
    id VARCHAR(255) PRIMARY KEY,
    email VARCHAR(255) NOT NULL REFERENCES kb_user_profile(email) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL REFERENCES kb_conversation_session(id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    citations_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    mode VARCHAR(32),
    web_search_enabled BOOLEAN NOT NULL DEFAULT false,
    error TEXT
);

CREATE INDEX IF NOT EXISTS kb_chat_message_session_created_idx
    ON kb_chat_message (email, session_id, created_at);

CREATE TABLE IF NOT EXISTS document_upload_audit (
    id VARCHAR(64) PRIMARY KEY,
    uploader_email VARCHAR(255) REFERENCES kb_user_profile(email) ON DELETE SET NULL,
    source TEXT NOT NULL,
    size BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    chunk_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS document_upload_audit_email_created_idx
    ON document_upload_audit (uploader_email, created_at DESC);

CREATE INDEX IF NOT EXISTS document_upload_audit_source_created_idx
    ON document_upload_audit (source, created_at DESC);
