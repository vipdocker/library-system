package com.example.library.reminder;

import com.example.library.support.IntegrationTestBase;
import com.example.library.domain.entity.*;
import com.example.library.domain.enums.*;
import com.example.library.domain.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DunningNotificationTest extends IntegrationTestBase {
    @Autowired NotificationService service;
    @Autowired DunningRecordRepository records;
    @Autowired LoanRepository loans;
    @Autowired PlatformTransactionManager manager;
    private Loan loan;
    private Long operator;
    @BeforeEach void fixture() {
        operator = testDataFactory.admin("admin", "Admin@12345").getId();
        var reader = testDataFactory.reader("reader","Reader@123","reader",ReaderStatus.ACTIVE);
        var book = testDataFactory.book("9787115428028","测试书名","作者",null);
        loan = testDataFactory.activeLoan(testDataFactory.copy(book,CopyStatus.AVAILABLE),reader.reader(),
                LocalDate.of(2026,10,1),LocalDate.of(2026,10,31),operator);
    }
    private Long notifyRecord(Long recordId) {
        return service.notifyDunning(loan.getId(), recordId);
    }
    private void register() {
        var record = records.saveAndFlush(new DunningRecord(loans.findById(loan.getId()).orElseThrow(),DunningMethod.PHONE,"secret admin note",operator,mutableClock.instant()));
        record.setNotificationId(notifyRecord(record.getId()));
        records.saveAndFlush(record);
    }
    @Test @DisplayName("AC-NTF-03: caller transaction creates separate fixed notices without private note")
    void separateFixedNotices() throws Exception {
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(s -> { register(); register(); });
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification",Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dunning_record WHERE notification_id IS NOT NULL",Long.class)).isEqualTo(2);
        mockMvc.perform(get("/api/notifications/me").with(user(testDataFactory.principal("reader"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].type").value("DUNNING"))
            .andExpect(jsonPath("$.items[0].content").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))))
            .andExpect(jsonPath("$.items[0].overdueDays").value(17))
            .andExpect(jsonPath("$.items[0].seq").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test @DisplayName("DOD-R1: manual record and notice rollback atomically")
    void rollback() {
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(s -> { register(); throw new IllegalStateException("caller failed"); }))
            .isInstanceOf(IllegalStateException.class).hasMessage("caller failed");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification",Long.class)).isZero();
        assertThat(records.count()).isZero();
    }
    @Test @DisplayName("AC-NTF-03: notification bridge requires caller transaction")
    void mandatoryTransaction() {
        assertThatThrownBy(() -> notifyRecord(999L)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
