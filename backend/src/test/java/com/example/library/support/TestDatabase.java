package com.example.library.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 每个测试 JVM 一个临时文件数据库（design-v0.1 第 5 节测试 profile 约定）。
 * 禁止测试写入 backend/data/library.db。
 * 用法：@DynamicPropertySource static void ds(DynamicPropertyRegistry r) { TestDatabase.register(r); }
 */
public final class TestDatabase {

    public static final String URL;

    static {
        try {
            Path dir = Files.createTempDirectory("library-test-db");
            Path db = dir.resolve("library-test.db");
            db.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            URL = "jdbc:sqlite:" + db.toAbsolutePath()
                    + "?foreign_keys=true&busy_timeout=5000&journal_mode=WAL";
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestDatabase() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
    }

    /** 每个测试前清表（按外键依赖顺序），并重置自增序列。 */
    public static void cleanAllTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("DELETE FROM dunning_record");
        jdbcTemplate.execute("DELETE FROM notification");
        jdbcTemplate.execute("DELETE FROM loan");
        jdbcTemplate.execute("DELETE FROM book_copy");
        jdbcTemplate.execute("DELETE FROM reader");
        jdbcTemplate.execute("DELETE FROM book");
        jdbcTemplate.execute("DELETE FROM category");
        jdbcTemplate.execute("DELETE FROM app_user");
        jdbcTemplate.execute("DELETE FROM sqlite_sequence");
        jdbcTemplate.execute("UPDATE system_proxy_settings SET enabled=0,host=NULL,port=NULL,revision=0,updated_at=NULL,updated_by=NULL WHERE id=1");
    }
}