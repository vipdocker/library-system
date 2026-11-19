package com.example.library.auth;

import com.example.library.auth.dto.ChangePasswordRequest;
import com.example.library.auth.dto.LoginRequest;
import com.example.library.auth.dto.UserInfo;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.security.CurrentUser;
import com.example.library.security.LibraryUserDetailsService;
import com.example.library.security.LibraryUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（design-v0.1 第 8.4 节 auth）：csrf / login / logout / me / password。
 * PUT /api/auth/password 由 T14 实现（D4① / AC-AUTH-10；SecurityConfig 已预置规则）。
 * 日志只记录用户名与原因类别，禁止输出口令、哈希、Session ID、CSRF Token（第 12 节）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final LibraryUserDetailsService userDetailsService;
    private final ChangePasswordService changePasswordService;

    public AuthController(AuthenticationManager authenticationManager,
                          HttpSessionSecurityContextRepository securityContextRepository,
                          CsrfTokenRepository csrfTokenRepository,
                          LibraryUserDetailsService userDetailsService,
                          ChangePasswordService changePasswordService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.userDetailsService = userDetailsService;
        this.changePasswordService = changePasswordService;
    }

    /** 就绪探针 + CSRF 引导：204，下发 XSRF-TOKEN Cookie。 */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        if (token != null) {
            // 触发 deferred token 渲染，确保 Cookie 写出
            token.getToken();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public UserInfo login(@Valid @RequestBody LoginRequest body,
                          HttpServletRequest request, HttpServletResponse response) {
        String username = body.username() == null ? "" : body.username().trim();
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, body.password()));
        } catch (DisabledException ex) {
            log.warn("auth.login.failed username={} reason=ACCOUNT_DISABLED", username);
            throw ApiException.of(ErrorCode.AUTH_ACCOUNT_DISABLED);
        } catch (BadCredentialsException | UsernameNotFoundException ex) {
            log.warn("auth.login.failed username={} reason=BAD_CREDENTIALS", username);
            throw ApiException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
        } catch (AuthenticationException ex) {
            log.warn("auth.login.failed username={} reason={}", username, ex.getClass().getSimpleName());
            throw ApiException.of(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        LibraryUserPrincipal principal = (LibraryUserPrincipal) authentication.getPrincipal();
        if (!principal.isEnabled()) {
            // 口令正确但账号停用（AC-AUTH-02）；不保存会话
            log.warn("auth.login.failed username={} reason=ACCOUNT_DISABLED", username);
            throw ApiException.of(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        // 防会话固定：认证成功后更换 Session ID（第 7 节）
        request.changeSessionId();
        // 认证成功后轮换 CSRF Token（第 7 节）；前端每次请求从 Cookie 重新读取
        CsrfToken rotated = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(rotated, request, response);

        log.info("auth.login.success userId={} username={}", principal.getUserId(), principal.getUsername());
        return UserInfo.from(principal);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
                                       Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    /** 当前用户。重新加载账号：已停用/已删除的账号会话在此失效（401）。 */
    @GetMapping("/me")
    public UserInfo me(@CurrentUser LibraryUserPrincipal principal) {
        LibraryUserPrincipal fresh;
        try {
            fresh = userDetailsService.loadUserByUsername(principal.getUsername());
        } catch (UsernameNotFoundException ex) {
            throw ApiException.of(ErrorCode.AUTH_REQUIRED);
        }
        if (!fresh.isEnabled()) {
            throw ApiException.of(ErrorCode.AUTH_REQUIRED);
        }
        return UserInfo.from(fresh);
    }

    /**
     * 修改本人口令（AC-AUTH-10，D4①）：204，当前会话保留；
     * 旧口令错误 400 AUTH_PASSWORD_MISMATCH；新口令不合规 400 VALIDATION_FAILED。
     * ADMIN 与 READER 都可调用（第 7 节授权矩阵，规则在 SecurityConfig 预置）。
     */
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@CurrentUser LibraryUserPrincipal principal,
                                               @Valid @RequestBody ChangePasswordRequest body) {
        changePasswordService.changePassword(principal, body);
        return ResponseEntity.noContent().build();
    }
}