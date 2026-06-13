package com.fox.aikbassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SqlQueryTool {

    private static final Logger log = LoggerFactory.getLogger(SqlQueryTool.class);
    private static final int MAX_ROWS = 100;

    private final JdbcTemplate jdbcTemplate;

    public SqlQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "对知识库业务表执行只读 SELECT 查询，返回最多100行")
    public List<Map<String, Object>> query(
            @ToolParam(description = "合法的只读 SELECT 语句") String sql) {
        long start = System.currentTimeMillis();
        validate(sql);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql).stream().limit(MAX_ROWS).toList();
        log.info("tool=sql_query elapsedMs={} rowCount={}", System.currentTimeMillis() - start, rows.size());
        return rows;
    }

    void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String normalized = sql.trim().toLowerCase();
        if (!normalized.startsWith("select")) {
            throw new IllegalArgumentException("仅允许 SELECT 查询");
        }
        if (normalized.matches(".*\\b(insert|update|delete|drop|alter|truncate|create|grant)\\b.*")) {
            throw new IllegalArgumentException("检测到禁止的写操作关键字");
        }
    }
}
