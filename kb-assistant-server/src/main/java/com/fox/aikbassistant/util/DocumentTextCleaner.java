package com.fox.aikbassistant.util;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档文本清洗与无效片段过滤工具。
 *
 * <p>主要用于入库前清理 PDF/Tika 解析产生的脏文本（控制字符、PDFBox 的
 * {@code (cid:xxx)} artifact、连续空白等），并过滤掉可读性过低或过短的片段，
 * 避免乱码内容污染向量库、浪费 embedding 成本以及影响引用展示。
 *
 * <p>清洗策略保守，保留中文、英文、数字与常见标点，不会按非 ASCII 比例粗暴删除，
 * 因此不会误删中文或代码、API 文档中的符号。
 */
public final class DocumentTextCleaner {

    /** 低于该有效字符长度的片段视为无意义。 */
    private static final int MIN_USEFUL_LENGTH = 20;

    /** 可读字符（字母/数字/汉字）占比低于该阈值的片段视为乱码。 */
    private static final double MIN_READABLE_RATIO = 0.35;

    private DocumentTextCleaner() {
    }

    /**
     * 清洗并过滤文档列表：先规范化文本，再剔除无效片段。
     */
    public static List<Document> clean(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(doc -> new Document(cleanText(doc.getText()), doc.getMetadata()))
                .filter(doc -> isUsefulText(doc.getText()))
                .toList();
    }

    /**
     * 规范化文本：去除控制字符与 PDF artifact，合并多余空白。
     */
    public static String cleanText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\u0000", "")
                .replaceAll("\\(cid:\\d+\\)", " ")
                .replaceAll("\\uFFFD+", " ")
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("[ \\t]*\\r?\\n[ \\t]*", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 判断文本是否值得入库：非空、长度达标且可读字符占比达标。
     */
    public static boolean isUsefulText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        int total = trimmed.codePointCount(0, trimmed.length());
        if (total < MIN_USEFUL_LENGTH) {
            return false;
        }
        long readable = trimmed.codePoints()
                .filter(DocumentTextCleaner::isReadable)
                .count();
        return readable * 1.0 / total >= MIN_READABLE_RATIO;
    }

    private static boolean isReadable(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) {
            return true;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
