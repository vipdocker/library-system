package com.example.library.catalog;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.example.library.common.error.ApiException;
import com.example.library.common.error.ErrorCode;
import com.example.library.common.error.ErrorResponse;
import com.example.library.common.proxy.ProxySnapshotProvider;
import com.example.library.common.util.IsbnUtils;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * Lookup has no repository, persistence operation or transaction; source ISBN is not proof of provenance.
 * Full cache hits complete immediately; everything else goes through the bounded single-flight worker.
 */
@Service
public class OpenLibraryService {
    private final OpenLibraryClient client;
    private final ProxySnapshotProvider proxySettings;
    private final OpenLibraryLookupState state =
            new OpenLibraryLookupState(System::nanoTime, Duration.ofSeconds(14), Duration.ofSeconds(1));

    public OpenLibraryService(OpenLibraryClient client, ProxySnapshotProvider proxySettings) {
        this.client = client;
        this.proxySettings = proxySettings;
    }

    OpenLibraryLookupState.Subscription subscribe(String raw) {
        if (raw == null || !IsbnUtils.isValid(raw)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "ISBN 格式或校验位错误", Map.of(),
                    List.of(new ErrorResponse.FieldErrorItem("isbn", "ISBN 格式或校验位错误")));
        }
        String isbn = IsbnUtils.normalize(raw);
        var hit = client.cached(isbn);
        if (hit != null && hit.isPresent()) { return OpenLibraryLookupState.completed(hit.get()); }
        var snapshot = proxySettings.snapshot();
        return state.subscribe(new OpenLibraryLookupState.Key(isbn, snapshot.enabled(), snapshot.revision()),
                () -> {
                    try { return client.lookup(isbn, snapshot); }
                    finally {
                        // Completion/cancel does not release the worker while Netty is still closing owned channels.
                        client.quiescence().toCompletableFuture().join();
                    }
                });
    }

    @PreDestroy
    void close() {
        state.close();
    }
}
