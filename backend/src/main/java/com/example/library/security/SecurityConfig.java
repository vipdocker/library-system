package com.example.library.security;

import com.example.library.common.config.LibraryProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * 安全配置（design-v0.1 第 7 节授权矩阵，全部 URL 规则一次写全；T01 之后只读，
 * 例外：T14 仅可确认 /api/auth/password 一行）。
 *
 * - Session + Cookie（HttpOnly、SameSite=Lax、30m，见 application.yml）；
 * - CSRF：CookieCsrfTokenRepository(XSRF-TOKEN / X-XSRF-TOKEN) + SPA 处理器；登录也受保护；
 * - 未认证 401 AUTH_REQUIRED（不重定向）；无权限 403 FORBIDDEN；CSRF 失败 403 CSRF_INVALID；
 * - D2：reader-self-renew=false 时续借仅 ADMIN；true 时 READER 也可（服务层再做归属校验）；
 * - D4①：预置 PUT /api/auth/password（authenticated），接口本身由 T14 实现；
 * - 未匹配的一律拒绝；ERROR dispatch 放行以便 404/500 走 /error 输出。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final LibraryProperties properties;

    public SecurityConfig(LibraryProperties properties) {
        this.properties = properties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder，默认 {bcrypt} 强度 10
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    public AuthenticationManager authenticationManager(LibraryUserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // 停用检查放到口令校验之后（AuthController），避免用错误口令探测账号是否存在；
        // 口令正确但账号停用 → 401 AUTH_ACCOUNT_DISABLED（AC-AUTH-02）。
        provider.setPreAuthenticationChecks(details -> {
        });
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CsrfTokenRepository csrfTokenRepository,
                                                   HttpSessionSecurityContextRepository securityContextRepository,
                                                   JsonAuthenticationEntryPoint authenticationEntryPoint,
                                                   JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        AuthorizationManager<org.springframework.security.web.access.intercept.RequestAuthorizationContext> renewRule =
                properties.getLoan().isReaderSelfRenew()
                        ? AuthorizationManagers.anyOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasRole("READER"))
                        : AuthorityAuthorizationManager.hasRole("ADMIN");

        http
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository).spa())
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        // D4①：T14 实现接口本体；规则在此预置
                        .requestMatchers(HttpMethod.PUT, "/api/auth/password").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/categories", "/api/books", "/api/books/{id}")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/books/{id}/cover").hasAnyRole("ADMIN", "READER")
                        .requestMatchers(HttpMethod.GET, "/api/readers/me", "/api/loans/me").hasRole("READER")
                        .requestMatchers("/api/notifications/me", "/api/notifications/me/**").hasRole("READER")
                        .requestMatchers(HttpMethod.POST, "/api/loans/{id}/renew").access(renewRule)
                        // 其余 /api/**（写操作、副本、读者、借阅、统计、报表）仅 ADMIN
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults());
        return http.build();
    }
}