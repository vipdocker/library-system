package com.example.library.seed;

import java.time.LocalDate;

import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import com.example.library.domain.enums.CopyStatus;
import com.example.library.domain.enums.ReaderStatus;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.CategoryRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.ReaderRepository;
import com.example.library.security.AdminBootstrap;
import com.example.library.support.AuthTestHelper;
import com.example.library.support.MutableClock;
import com.example.library.support.TestDataFactory;
import com.example.library.support.TestDatabase;
import com.example.library.support.TestSupportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DOD-06 / AC-AUTH-11（dev 默认口令实际生效）：演示数据内容、一致性、幂等与演示账号登录。
 * 独立上下文：test+dev profile，显式打开 bootstrap 与 seed（临时数据库，不碰开发库）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
@TestPropertySource(properties = {"lib.admin.bootstrap=true", "lib.seed.demo=true"})
@Import(TestSupportConfig.class)
class DemoDataLoaderTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock mutableClock;
    @Autowired
    private TestDataFactory testDataFactory;
    @Autowired
    private AuthTestHelper authTestHelper;
    @Autowired
    private AdminBootstrap adminBootstrap;
    @Autowired
    private DemoDataLoader demoDataLoader;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private ReaderRepository readerRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private BookRepository bookRepository;
    @Autowired
    private BookCopyRepository bookCopyRepository;
    @Autowired
    private LoanRepository loanRepository;

    @BeforeEach
    void reseedFromEmpty() {
        TestDatabase.cleanAllTables(jdbcTemplate);
        mutableClock.reset();
        adminBootstrap.run(null);
        demoDataLoader.run(null);
    }

    private LocalDate today() {
        return LocalDate.now(mutableClock);
    }

    @Test
    @DisplayName("DOD-06：演示数据完整（5 分类/12 书/24 副本/3 读者/10 借阅），reader2 逾期、reader1 在借、副本状态一致")
    void seedContentAndConsistency() {
        assertThat(categoryRepository.count()).isEqualTo(5);
        assertThat(bookRepository.count()).isEqualTo(12);
        assertThat(bookCopyRepository.count()).isEqualTo(24);
        assertThat(readerRepository.count()).isEqualTo(3);
        assertThat(loanRepository.count()).isEqualTo(10);

        var reader1 = readerRepository.findByCardNo(cardNoOf("reader1")).orElseThrow();
        var reader2 = readerRepository.findByCardNo(cardNoOf("reader2")).orElseThrow();
        var reader3 = readerRepository.findByCardNo(cardNoOf("reader3")).orElseThrow();
        assertThat(reader1.getCardNo()).matches("R\\d{6}");
        assertThat(reader1.getStatus()).isEqualTo(ReaderStatus.ACTIVE);
        assertThat(reader3.getStatus()).isEqualTo(ReaderStatus.DISABLED);
        assertThat(appUserRepository.findByUsernameIgnoreCase("reader3").orElseThrow().isEnabled()).isFalse();

        assertThat(loanRepository.countActiveByReaderId(reader1.getId())).isEqualTo(1);
        assertThat(loanRepository.existsOverdueByReaderId(reader1.getId(), today())).isFalse();
        assertThat(loanRepository.countActiveByReaderId(reader2.getId())).isEqualTo(1);
        assertThat(loanRepository.existsOverdueByReaderId(reader2.getId(), today())).isTrue();

        // 逾期借阅：借于 today-40，应还 today-10
        Loan overdue = loanRepository.findAll().stream()
                .filter(l -> l.getReader().getId().equals(reader2.getId()) && l.getReturnedAt() == null)
                .findFirst().orElseThrow();
        assertThat(overdue.getBorrowDate()).isEqualTo(today().minusDays(40));
        assertThat(overdue.getDueDate()).isEqualTo(today().minusDays(10));

        // 在借副本必须是 BORROWED，其余 AVAILABLE
        long borrowed = bookCopyRepository.findAll().stream()
                .filter(c -> c.getStatus() == CopyStatus.BORROWED).count();
        assertThat(borrowed).isEqualTo(2);
        for (BookCopy copy : bookCopyRepository.findAll()) {
            assertThat(copy.getBarcode()).matches("C\\d{8}");
            boolean active = loanRepository.findActiveByCopyId(copy.getId()).isPresent();
            assertThat(copy.getStatus() == CopyStatus.BORROWED).isEqualTo(active);
        }
        // 历史借阅分布最近 60 天
        long history = loanRepository.findAll().stream().filter(l -> l.getReturnedAt() != null).count();
        assertThat(history).isEqualTo(8);
    }

    private String cardNoOf(String username) {
        Long userId = appUserRepository.findByUsernameIgnoreCase(username).orElseThrow().getId();
        return readerRepository.findByUserId(userId).orElseThrow().getCardNo();
    }

    @Test
    @DisplayName("DOD-06：重复执行不重复写入（幂等），不覆盖管理员口令")
    void idempotent() {
        String hashBefore = appUserRepository.findByUsernameIgnoreCase("admin").orElseThrow().getPasswordHash();
        demoDataLoader.run(null);
        demoDataLoader.seedIfEmpty();
        assertThat(bookRepository.count()).isEqualTo(12);
        assertThat(bookCopyRepository.count()).isEqualTo(24);
        assertThat(readerRepository.count()).isEqualTo(3);
        assertThat(loanRepository.count()).isEqualTo(10);
        assertThat(appUserRepository.findByUsernameIgnoreCase("admin").orElseThrow().getPasswordHash())
                .isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("DOD-06/AC-AUTH-11：dev 默认管理员与演示读者可登录；停用读者被拒绝")
    void demoAccountsCanLogin() throws Exception {
        var adminSession = authTestHelper.loginAs("admin", "Admin@12345");
        mockMvc.perform(get("/api/auth/me").session(adminSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        var readerSession = authTestHelper.loginAs("reader1", "Reader@123");
        mockMvc.perform(get("/api/auth/me").session(readerSession.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("READER"))
                .andExpect(jsonPath("$.displayName").value("演示读者一"));

        String token = authTestHelper.freshCsrfToken();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"reader3\",\"password\":\"Reader@123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_DISABLED"));
    }
}