package com.example.library.catalog.settings;

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

/**
 * Committed-then-published configuration (showapi-design-v1 §5): a failed transaction changes
 * neither the database nor the snapshot; a successful commit publishes an immutable snapshot for
 * the next query. Startup load failures propagate instead of silently falling back to defaults.
 * Saving settings never calls ShowAPI and never touches proxy settings. Logs carry no key material.
 */
@Service
@DependsOnDatabaseInitialization
public class ShowApiSettingsService implements ShowApiSettingsProvider {
    private final ShowApiSettingsRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicReference<ShowApiSettings> current = new AtomicReference<>();

    ShowApiSettingsService(ShowApiSettingsRepository repository, PlatformTransactionManager manager, Clock clock) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @PostConstruct
    public synchronized void reload() { current.set(repository.load()); }

    @Override
    public ShowApiSettings snapshot() { return Objects.requireNonNull(current.get(), "showapi settings not initialized"); }

    public synchronized ShowApiSettings replace(JsonNode body, String actor) {
        ShowApiSettingsRequest request = ShowApiSettingsRequest.parse(body);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("showapi settings replacement must own its transaction");
        }
        ShowApiSettings committed = transaction.execute(status -> repository.replace(request, actor, clock.instant()));
        current.set(Objects.requireNonNull(committed));
        LoggerFactory.getLogger(ShowApiSettingsService.class).info(
                "showapi.settings actor={} action={} revision={} enabled={} result=committed",
                actor, request.action(), committed.revision(), committed.enabled());
        return committed;
    }
}