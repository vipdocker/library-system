package com.example.library.domain.repository;

import java.util.Optional;

import com.example.library.domain.entity.AppUser;
import com.example.library.domain.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 账号 Repository（共享契约，T01 预置；T05 可新增方法，禁止修改已有签名）。
 * IgnoreCase 与数据库 COLLATE NOCASE 语义一致。
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByRole(Role role);
}