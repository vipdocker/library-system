package com.example.library.loan;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.domain.entity.BookCopy;
import com.example.library.domain.entity.Loan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 借/还/续借 INFO 事件日志（design-v0.1 第 12 节；DOD-08 日志部分的模块证据）：
 * 事件含 loanId/readerId/copyId/operatorId；任何日志不输出口令或 Token。
 */
class LoanEventLogTest extends LoanTestBase {

    private ListAppender<ILoggingEvent> appender;
    private Logger packageLogger;

    @BeforeEach
    void attachAppender() {
        packageLogger = (Logger) LoggerFactory.getLogger("com.example.library");
        appender = new ListAppender<>();
        appender.start();
        packageLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        packageLogger.detachAppender(appender);
        appender.stop();
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("第12节/DOD-08：借、还、续借输出 INFO 事件（loanId/readerId/copyId/operatorId），日志不含口令或 Token")
    void loanEventsLoggedWithoutSecrets() throws Exception {
        BookCopy copy1 = newCopy(newBook("日志借书"));
        long loanId1 = idOf(borrowOk(readerA.reader().getCardNo(), copy1.getBarcode()));
        BookCopy copy2 = newCopy(newBook("日志续借"));
        Loan loan2 = activeLoan(copy2, readerA, today().minusDays(10), today().plusDays(20));

        asAdmin(post("/api/loans/" + loan2.getId() + "/renew")).andExpect(status().isOk());
        asAdmin(post("/api/loans/" + loanId1 + "/return")).andExpect(status().isOk());
        // 一次失败登录：WARN 日志也不得包含口令
        String token = authTestHelper.freshCsrfToken();
        mockMvc.perform(post("/api/auth/login")
                        .cookie(new jakarta.servlet.http.Cookie("XSRF-TOKEN", token))
                        .header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"loan-reader-a\",\"password\":\"WrongPass@999\"}"))
                .andExpect(status().isUnauthorized());

        List<String> messages = messages();
        assertThat(messages).anyMatch(m -> m.equals(
                "loan.borrow loanId=%d readerId=%d copyId=%d operatorId=%d"
                        .formatted(loanId1, readerA.reader().getId(), copy1.getId(), adminUser.getId())));
        assertThat(messages).anyMatch(m -> m.equals(
                "loan.renew loanId=%d readerId=%d copyId=%d operatorId=%d"
                        .formatted(loan2.getId(), readerA.reader().getId(), copy2.getId(), adminUser.getId())));
        assertThat(messages).anyMatch(m -> m.equals(
                "loan.return loanId=%d readerId=%d copyId=%d operatorId=%d"
                        .formatted(loanId1, readerA.reader().getId(), copy1.getId(), adminUser.getId())));
        assertThat(appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("loan."))
                .allMatch(e -> e.getLevel() == Level.INFO)).isTrue();
        // 任何捕获的日志都不得包含口令、哈希或本次使用的 CSRF Token
        assertThat(messages).allSatisfy(m -> assertThat(m)
                .doesNotContain(ADMIN_PASSWORD)
                .doesNotContain(READER_PASSWORD)
                .doesNotContain("WrongPass@999")
                .doesNotContain(token)
                .doesNotContain(admin.csrfToken())
                .doesNotContain("$2a$"));
    }
}