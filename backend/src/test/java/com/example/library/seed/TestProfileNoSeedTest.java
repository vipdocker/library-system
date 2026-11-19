package com.example.library.seed;

import com.example.library.domain.enums.Role;
import com.example.library.domain.repository.AppUserRepository;
import com.example.library.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** DOD-06：测试 profile 不写入任何种子数据，AdminBootstrap 关闭。 */
class TestProfileNoSeedTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    @DisplayName("DOD-06：test profile 无 DemoDataLoader Bean、无管理员、无业务数据")
    void testProfileHasNoSeed() {
        assertThat(applicationContext.getBeanNamesForType(DemoDataLoader.class)).isEmpty();
        assertThat(appUserRepository.existsByRole(Role.ADMIN)).isFalse();
        assertThat(appUserRepository.count()).isZero();
    }
}