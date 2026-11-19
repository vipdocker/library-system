package com.example.library.security;

import java.time.Clock;

import com.example.library.common.config.LibraryProperties;
import com.example.library.domain.entity.AppUser;
import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始管理员（AC-AUTH-11 / D5）：
 * - 不存在 ADMIN 时创建 lib.admin.username；
 * - 口令取 LIB_ADMIN_PASSWORD；为空且 dev profile → 开发默认口令 Admin@12345 并 WARN；
 * - 为空且非 dev → 启动失败；
 * - 幂等：已有 ADMIN 时不做任何事，不覆盖已有口令。
 * 测试 profile 通过 lib.admin.bootstrap=false 关闭。
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

    static final String DEV_DEFAULT_PASSWORD = "Admin@12345";

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final LibraryProperties properties;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final Clock clock;

    public AdminBootstrap(LibraryProperties properties, AppUserRepository appUserRepository,
                          PasswordEncoder passwordEncoder, Environment environment, Clock clock) {
        this.properties = properties;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.getAdmin().isBootstrap()) {
            return;
        }
        if (appUserRepository.existsByRole(Role.ADMIN)) {
            return;
        }
        String password = properties.getAdmin().getInitialPassword();
        if (password == null || password.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                password = DEV_DEFAULT_PASSWORD;
                log.warn("admin.bootstrap 正在使用开发默认口令（仅限本地开发；可用 LIB_ADMIN_PASSWORD 覆盖）");
            } else {
                throw new IllegalStateException(
                        "LIB_ADMIN_PASSWORD 未设置且当前不是 dev profile，拒绝启动（AC-AUTH-11）");
            }
        }
        String username = properties.getAdmin().getUsername();
        AppUser admin = new AppUser(username, passwordEncoder.encode(password), Role.ADMIN, true, clock.instant());
        appUserRepository.save(admin);
        log.info("admin.bootstrap.created username={}", username);
    }
}