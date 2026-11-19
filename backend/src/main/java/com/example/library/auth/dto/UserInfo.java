package com.example.library.auth.dto;

import com.example.library.security.LibraryUserPrincipal;

/**
 * UserInfo（design-v0.1 第 8.3 节）：
 * { id, username, role, displayName, readerId|null, cardNo|null }。不含任何口令字段。
 */
public record UserInfo(Long id, String username, String role, String displayName, Long readerId, String cardNo) {

    public static UserInfo from(LibraryUserPrincipal principal) {
        return new UserInfo(principal.getUserId(), principal.getUsername(), principal.getRole().name(),
                principal.getDisplayName(), principal.getReaderId(), principal.getCardNo());
    }
}