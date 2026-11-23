package com.example.library.support;

import com.example.library.domain.repository.BookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最小纵向链路：空库 Flyway V1 迁移成功 + ddl-auto=validate 通过 + 测试 profile 无种子（DOD-01/DOD-06 支撑）。
 */
class ContextLoadsTest extends IntegrationTestBase {

    @Autowired
    private BookRepository bookRepository;

    @Test
    @DisplayName("DOD-01/DOD-06：上下文启动（迁移+validate 通过），测试 profile 无任何种子数据")
    void contextLoadsWithMigratedSchemaAndNoSeedData() {
        assertThat(bookRepository.count()).isZero();
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN "
                        + "('app_user','reader','category','book','book_copy','loan')", Integer.class);
        assertThat(tables).isEqualTo(6);
        java.util.List<String> reminderTables = jdbcTemplate.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN "
                        + "('notification','dunning_record') ORDER BY name", String.class);
        assertThat(reminderTables).containsExactly("dunning_record", "notification");
        java.util.List<String> flywayApplied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);
        assertThat(flywayApplied).containsExactly("1", "2", "3", "4", "5");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM system_proxy_settings WHERE id=1 AND enabled=0 AND host IS NULL AND port IS NULL AND revision=0 AND updated_at IS NULL AND updated_by IS NULL", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM showapi_settings WHERE id=1 AND enabled=0 AND app_key IS NULL AND revision=0 AND updated_at IS NULL AND updated_by IS NULL", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM book_cover", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pragma_table_info('book') WHERE name='open_library_isbn'", Long.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pragma_table_info('book') WHERE name IN ('source_provider','source_isbn')", Long.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM pragma_table_info('book_cover') WHERE name='provider'", Long.class))
                .isEqualTo(1);
    }
}