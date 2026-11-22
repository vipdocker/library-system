package com.example.library.common.proxy;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
@DependsOnDatabaseInitialization
public class ProxySettingsService implements ProxySnapshotProvider {
    private final ProxySettingsRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicReference<ProxySnapshot> current = new AtomicReference<>();

    ProxySettingsService(ProxySettingsRepository repository, PlatformTransactionManager manager, Clock clock) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @PostConstruct
    public synchronized void reload() { current.set(repository.load()); }

    @Override
    public ProxySnapshot snapshot() { return Objects.requireNonNull(current.get(), "proxy settings not initialized"); }

    public synchronized ProxySnapshot replace(JsonNode body, String actor) {
        ProxySettingsRequest request = ProxySettingsRequest.parse(body);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("proxy replacement must own its transaction");
        }
        ProxySnapshot committed = transaction.execute(status -> repository.replace(request, actor, clock.instant()));
        current.set(Objects.requireNonNull(committed));
        LoggerFactory.getLogger(ProxySettingsService.class).info("proxy.settings actor={} revision={} enabled={} result=committed",
                actor, committed.revision(), committed.enabled());
        return committed;
    }
}
