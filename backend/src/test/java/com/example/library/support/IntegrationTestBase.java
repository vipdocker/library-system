package com.example.library.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 集成测试基类（共享契约：T01 之后只读）。
 * - Boot 4 实际生效注解：org.springframework.boot.test.context.SpringBootTest
 *   + org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc（包名已变，勿用旧 web.servlet 包）；
 * - 每 JVM 一个临时文件 SQLite（TestDatabase），Flyway 迁移 + ddl-auto=validate；
 * - 每个测试前清表并重置 MutableClock；
 * - 测试 profile：无种子数据、AdminBootstrap 关闭。
 * 注入即用：mockMvc、jdbcTemplate、mutableClock、testDataFactory、authTestHelper。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSupportConfig.class)
public abstract class IntegrationTestBase {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock mutableClock;

    @Autowired
    protected TestDataFactory testDataFactory;

    @Autowired
    protected AuthTestHelper authTestHelper;

    @BeforeEach
    void cleanDatabaseAndClock() {
        TestDatabase.cleanAllTables(jdbcTemplate);
        mutableClock.reset();
    }
}