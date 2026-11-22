package com.example.library.common.proxy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxySettingsServiceTest {
    @Test
    void acProxy02CommitFailureNeverPublishesUncommittedSnapshot() {
        var old = new ProxySnapshot(false, null, null, 0, null);
        var changed = new ProxySnapshot(true, "localhost", 9999, 1, Instant.EPOCH);
        var repo = mock(ProxySettingsRepository.class);
        when(repo.load()).thenReturn(old);
        when(repo.replace(any(), eq("admin"), any())).thenReturn(changed);
        var manager = mock(PlatformTransactionManager.class);
        var tx = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(tx);
        doThrow(new IllegalStateException("commit failed fixture")).when(manager).commit(tx);
        var service = new ProxySettingsService(repo, manager, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        service.reload();
        assertThatThrownBy(() -> service.replace(JsonMapper.builder().build().readTree("{\"enabled\":true,\"host\":\"localhost\",\"port\":9999}"), "admin"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.snapshot()).isSameAs(old);
        verify(repo, times(1)).replace(any(), eq("admin"), any());
    }

    @Test
    void acProxy02PublishedOnlyAfterCommitAndLastSuccessfulUpdateWins() throws Exception {
        var repo = mock(ProxySettingsRepository.class);
        when(repo.load()).thenReturn(new ProxySnapshot(false, null, null, 0, null));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        doAnswer(inv -> { entered.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; }).when(manager).commit(any());
        var enabled = new ProxySnapshot(true, "localhost", 9999, 1, Instant.EPOCH);
        when(repo.replace(any(), anyString(), any())).thenReturn(enabled, new ProxySnapshot(false, "localhost", 9999, 2, Instant.EPOCH));
        var service = new ProxySettingsService(repo, manager, Clock.systemUTC()); service.reload();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.replace(JsonMapper.builder().build().readTree("{\"enabled\":true,\"host\":\"localhost\",\"port\":9999}"), "a"));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var next = executor.submit(() -> service.replace(JsonMapper.builder().build().readTree("{\"enabled\":false,\"host\":\"localhost\",\"port\":9999}"), "b"));
            assertThat(service.snapshot().revision()).isZero();
            release.countDown(); assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(enabled);
            assertThat(next.get(3, TimeUnit.SECONDS).revision()).isEqualTo(2);
            assertThat(service.snapshot().enabled()).isFalse();
        } finally { release.countDown(); }
    }

    @Test
    void acProxy02FailedStartupDoesNotInventDefaultConfiguration() {
        var repo = mock(ProxySettingsRepository.class); when(repo.load()).thenThrow(new IllegalStateException("corrupt fixture"));
        var service = new ProxySettingsService(repo, mock(PlatformTransactionManager.class), Clock.systemUTC());
        assertThatThrownBy(service::reload).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(service::snapshot).isInstanceOf(NullPointerException.class);
    }
}
