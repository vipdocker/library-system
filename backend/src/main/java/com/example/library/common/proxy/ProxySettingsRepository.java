package com.example.library.common.proxy;

import java.time.Instant;
import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProxySettingsRepository {
    private final JdbcClient jdbc;
    ProxySettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    ProxySnapshot load() {
        return jdbc.sql("SELECT enabled,host,port,revision,updated_at FROM system_proxy_settings WHERE id=1")
                .query((rs, row) -> new ProxySnapshot(rs.getInt("enabled") == 1, rs.getString("host"),
                        (Integer) rs.getObject("port"), rs.getLong("revision"),
                        rs.getObject("updated_at") == null ? null : Instant.ofEpochMilli(rs.getLong("updated_at"))))
                .single();
    }

    ProxySnapshot replace(ProxySettingsRequest request, String actor, Instant now) {
        int changed = jdbc.sql("UPDATE system_proxy_settings SET enabled=:enabled,host=:host,port=:port,"
                        + "revision=revision+1,updated_at=:now,updated_by=:actor WHERE id=1")
                .param("enabled", request.enabled() ? 1 : 0).param("host", request.host(), Types.VARCHAR)
                .param("port", request.port(), Types.INTEGER).param("now", now.toEpochMilli()).param("actor", actor).update();
        if (changed != 1) { throw new IllegalStateException("missing proxy settings"); }
        return load();
    }
}
