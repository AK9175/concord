package com.collabdoc.metadata;

import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;

public final class Main {

    private static final int PORT = 8083;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String jdbcUrl = System.getenv().getOrDefault("CONCORD_DB_URL", "jdbc:postgresql://localhost:5432/concord");
        String username = System.getenv().getOrDefault("CONCORD_DB_USER", "concord");
        String password = System.getenv().getOrDefault("CONCORD_DB_PASSWORD", "concord");

        HikariDataSource dataSource = PostgresDataSources.create(jdbcUrl, username, password);
        DocumentMetadataServer server = new DocumentMetadataServer(
                PORT, new PostgresDocumentMetadataStore(dataSource), new PostgresDocumentRosterStore(dataSource));
        server.start();
    }
}
