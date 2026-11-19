package com.example.library.auth;

import java.time.Clock;
import java.time.ZoneId;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.library.common.config.LibraryProperties;
import com.example.library.domain.entity.AppUser;
import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.security.AdminBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AC-AUTH-11：初始管理员创建、dev 默认口令 + WARN、非 dev 缺口令启动失败、幂等。 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private Environment environment;

    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final LibraryProperties properties = new LibraryProperties();
    private final Clock clock = Clock.fixed(java.time.Instant.parse("2026-11-17T02:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new AdminBootstrap(properties, appUserRepository, passwordEncoder, environment, clock);
    }

    @Test
    @DisplayName("AC-AUTH-11：无 ADMIN 时用 LIB_ADMIN_PASSWORD 创建管理员（{bcrypt} 存储）")
    void createsAdminWithConfiguredPassword() {
        properties.getAdmin().setInitialPassword("Str0ngPass1");
        when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        bootstrap.run(null);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(captor.capture());
        AppUser admin = captor.getValue();
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}$2");
        assertThat(passwordEncoder.matches("Str0ngPass1", admin.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("AC-AUTH-11：dev profile 且口令为空 → 使用开发默认口令 Admin@12345 并输出 WARN")
    void devDefaultPasswordWithWarn() {
        properties.getAdmin().setInitialPassword("");
        when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        Logger logger = (Logger) LoggerFactory.getLogger(AdminBootstrap.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            bootstrap.run(null);
        } finally {
            logger.detachAppender(appender);
        }
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(captor.capture());
        assertThat(passwordEncoder.matches("Admin@12345", captor.getValue().getPasswordHash())).isTrue();
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("开发默认口令");
        });
        // WARN 与日志中不得出现口令本身
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain("Admin@12345"));
    }

    @Test
    @DisplayName("AC-AUTH-11：非 dev profile 且口令为空 → 启动失败")
    void nonDevWithoutPasswordFailsStartup() {
        properties.getAdmin().setInitialPassword("");
        when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIB_ADMIN_PASSWORD");
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-AUTH-11：已存在 ADMIN 时幂等，不覆盖已有口令")
    void idempotentWhenAdminExists() {
        when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(true);
        bootstrap.run(null);
        verify(appUserRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-AUTH-11：lib.admin.bootstrap=false 时完全关闭（测试 profile）")
    void disabledByProperty() {
        properties.getAdmin().setBootstrap(false);
        bootstrap.run(null);
        verifyNoInteractions(appUserRepository);
    }

    @Test
    @DisplayName("AC-AUTH-11：管理员用户名取 lib.admin.username")
    void customUsername() {
        properties.getAdmin().setUsername("root-admin");
        properties.getAdmin().setInitialPassword("Str0ngPass1");
        when(appUserRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        bootstrap.run(null);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("root-admin");
    }
}