package com.fox.aikbassistant.util;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTextCleanerTest {

    @Test
    void cleanText_removesPdfArtifactsAndControlChars() {
        String dirty = "Hello\u0000 world(cid:12) \uFFFD\uFFFD  foo\t\tbar";
        String cleaned = DocumentTextCleaner.cleanText(dirty);

        assertThat(cleaned).doesNotContain("\u0000");
        assertThat(cleaned).doesNotContain("(cid:12)");
        assertThat(cleaned).doesNotContain("\uFFFD");
        assertThat(cleaned).contains("Hello world");
        assertThat(cleaned).contains("foo bar");
    }

    @Test
    void isUsefulText_keepsNormalChinese() {
        assertThat(DocumentTextCleaner.isUsefulText("这是一段足够长度的正常中文文本内容用于测试")).isTrue();
    }

    @Test
    void isUsefulText_keepsNormalEnglish() {
        assertThat(DocumentTextCleaner.isUsefulText("This is a perfectly normal english sentence for testing.")).isTrue();
    }

    @Test
    void isUsefulText_rejectsTooShort() {
        assertThat(DocumentTextCleaner.isUsefulText("短")).isFalse();
        assertThat(DocumentTextCleaner.isUsefulText("   ")).isFalse();
    }

    @Test
    void isUsefulText_rejectsGarbledSymbols() {
        assertThat(DocumentTextCleaner.isUsefulText("•·•·•·•·•·•·•·•·•·•·•·•·•·•·•·•·")).isFalse();
    }

    @Test
    void clean_filtersOutGarbledDocumentsButKeepsValidOnes() {
        Document valid = new Document("这是一段足够长度的正常中文文本内容用于测试过滤逻辑");
        Document garbled = new Document("••••••••••••••••••••••••••••");

        List<Document> result = DocumentTextCleaner.clean(List.of(valid, garbled));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getText()).contains("正常中文文本");
    }
}
