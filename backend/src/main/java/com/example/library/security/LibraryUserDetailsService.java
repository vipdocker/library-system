package com.example.library.security;

import com.example.library.domain.entity.AppUser;
import com.example.library.domain.entity.Reader;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.domain.repository.ReaderRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 按用户名（忽略大小写）加载账号与读者档案。
 * 停用账号仍会加载（enabled=false），由登录流程拒绝（AUTH_ACCOUNT_DISABLED），
 * 避免在口令校验前泄露账号是否存在。
 */
@Service
public class LibraryUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;
    private final ReaderRepository readerRepository;

    public LibraryUserDetailsService(AppUserRepository appUserRepository, ReaderRepository readerRepository) {
        this.appUserRepository = appUserRepository;
        this.readerRepository = readerRepository;
    }

    @Override
    public LibraryUserPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = appUserRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        Reader reader = user.getRole() == com.example.library.domain.enums.Role.READER
                ? readerRepository.findByUserId(user.getId()).orElse(null)
                : null;
        return new LibraryUserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole(),
                user.isEnabled(),
                reader == null ? null : reader.getId(),
                reader == null ? null : reader.getCardNo(),
                reader == null ? user.getUsername() : reader.getName());
    }
}