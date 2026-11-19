package com.example.library.security;

import java.util.Collection;
import java.util.List;

import com.example.library.domain.enums.Role;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 登录主体。读者身份（readerId/cardNo）只从这里取得，接口不接收 readerId 参数（防 IDOR）。
 * passwordHash 在认证成功后由 ProviderManager.eraseCredentials 清除；任何响应不得输出它。
 * Security 7：eraseCredentials 来自 CredentialsContainer（UserDetails 不再声明）。
 */
public class LibraryUserPrincipal implements UserDetails, CredentialsContainer {

    private final Long userId;
    private final String username;
    private String passwordHash;
    private final Role role;
    private final boolean enabled;
    private final Long readerId;
    private final String cardNo;
    private final String displayName;

    public LibraryUserPrincipal(Long userId, String username, String passwordHash, Role role, boolean enabled,
                                Long readerId, String cardNo, String displayName) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.readerId = readerId;
        this.cardNo = cardNo;
        this.displayName = displayName;
    }

    public Long getUserId() {
        return userId;
    }

    public Role getRole() {
        return role;
    }

    public Long getReaderId() {
        return readerId;
    }

    public String getCardNo() {
        return cardNo;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }
}