package com.example.library.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.example.library.domain.repository.AppUserRepository;
import com.example.library.domain.repository.BookCopyRepository;
import com.example.library.domain.repository.BookRepository;
import com.example.library.domain.repository.CategoryRepository;
import com.example.library.domain.repository.LoanRepository;
import com.example.library.domain.repository.ReaderRepository;
import com.example.library.security.LibraryUserDetailsService;

/**
 * 测试支撑装配（共享契约：T01 之后只读）。
 * IntegrationTestBase 与其他独立上下文通过 @Import(TestSupportConfig.class) 引入。
 * MutableClock 为 @Primary Clock，覆盖生产 ClockConfig。
 */
@TestConfiguration
public class TestSupportConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return MutableClock.fixedDefault();
    }

    @Bean
    public TestDataFactory testDataFactory(AppUserRepository appUserRepository, ReaderRepository readerRepository,
                                           CategoryRepository categoryRepository, BookRepository bookRepository,
                                           BookCopyRepository bookCopyRepository, LoanRepository loanRepository,
                                           PasswordEncoder passwordEncoder,
                                           LibraryUserDetailsService userDetailsService, MutableClock clock) {
        return new TestDataFactory(appUserRepository, readerRepository, categoryRepository, bookRepository,
                bookCopyRepository, loanRepository, passwordEncoder, userDetailsService, clock);
    }

    @Bean
    @org.springframework.context.annotation.Lazy
    public AuthTestHelper authTestHelper(MockMvc mockMvc) {
        return new AuthTestHelper(mockMvc);
    }
}