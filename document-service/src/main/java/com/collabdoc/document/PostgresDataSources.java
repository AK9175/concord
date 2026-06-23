package com.collabdoc.document;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Builds a connection-pooled DataSource for Postgres. A hand-rolled pool is its own source of bugs. */
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
