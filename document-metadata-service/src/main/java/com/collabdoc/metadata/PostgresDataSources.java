package com.collabdoc.metadata;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Builds a connection-pooled DataSource for Postgres. Deliberately
 * duplicated (not shared) from document-service's identical class --
 * this module has no dependency on document-service and isn't meant to
 * gain one just for a five-line utility; each service module stays
 * independently deployable.
 */
public final class PostgresDataSources {

    private PostgresDataSources() {
    }

    public static HikariDataSource create(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        return new HikariDataSource(config);
    }
}
