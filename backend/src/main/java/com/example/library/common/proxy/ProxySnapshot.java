package com.example.library.common.proxy;

import java.time.Instant;

public record ProxySnapshot(boolean enabled, String host, Integer port, long revision, Instant updatedAt) {
    public ProxySnapshot {
        host = ProxyAddress.normalize(host);
        if ((host == null) != (port == null) || port != null && (port < 1 || port > 65535)
                || enabled && host == null || revision < 0) {
            throw new IllegalArgumentException("invalid proxy settings");
        }
    }
}
