package com.example.library.auth;

import java.time.Clock;

import com.example.library.auth.dto.ChangePasswordRequest;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.domain.entity.AppUser;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.security.LibraryUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 修改本人口令（design-v0.1 第 7/8.4 节，D4① / AC-AUTH-10，T14）。
 * - 用户身份只从 principal.userId 取得，不接收任何用户标识参数（防 IDOR，第 7 节）；
 * - 旧口令不匹配 → 400 AUTH_PASSWORD_MISMATCH；新口令策略由 DTO 的 @ValidPassword 校验（400 VALIDATION_FAILED）；
 * - 成功后只更新 password_hash（BCrypt，DelegatingPasswordEncoder），不触碰会话：当前会话保留，
 *   之后登录只能用新口令；
 * - 日志只记录 userId/用户名与原因类别，禁止输出口令或哈希（第 12 节）。
 */
@Service
public class ChangePasswordService {

    private static final Logger log = LoggerFactory.getLogger(ChangePasswordService.class);

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public ChangePasswordService(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder, Clock clock) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** PUT /api/auth/password：校验旧口令后更新哈希；204 由控制器返回。 */
    @Transactional
    public void changePassword(LibraryUserPrincipal principal, ChangePasswordRequest request) {
        AppUser user = appUserRepository.findById(principal.getUserId())
                .orElseThrow(() -> ApiException.of(ErrorCode.AUTH_REQUIRED));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            log.warn("auth.password.change.failed userId={} username={} reason=OLD_PASSWORD_MISMATCH",
                    user.getId(), user.getUsername());
            throw ApiException.of(ErrorCode.AUTH_PASSWORD_MISMATCH);
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setUpdatedAt(clock.instant());
        appUserRepository.saveAndFlush(user);
        log.info("auth.password.change.success userId={} username={}", user.getId(), user.getUsername());
    }
}