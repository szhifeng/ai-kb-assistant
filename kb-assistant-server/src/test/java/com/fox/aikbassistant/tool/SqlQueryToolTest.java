package com.fox.aikbassistant.tool;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SqlQueryToolTest {

    private final SqlQueryTool tool = new SqlQueryTool(mock(JdbcTemplate.class));

    @Test
    void rejectsNonSelect() {
        assertThatThrownBy(() -> tool.query("DELETE FROM users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.query("DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.query("update t set a=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsSelect() {
        assertThatCode(() -> tool.validate("SELECT * FROM t"))
                .doesNotThrowAnyException();
    }
}
