package com.collabdoc.metadata;

import com.collabdoc.metadata.grpc.ConnectionTierEvictClient;
import com.collabdoc.metadata.grpc.DocumentServiceDeleteClient;
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

        String documentServiceHost = System.getenv().getOrDefault("DOCUMENT_SERVICE_HOST", "localhost");
        int documentServicePort = Integer.parseInt(System.getenv().getOrDefault("DOCUMENT_SERVICE_PORT", "9090"));
        String connectionTierHost = System.getenv().getOrDefault("CONNECTION_TIER_ADMIN_HOST", "localhost");
        int connectionTierPort = Integer.parseInt(System.getenv().getOrDefault("CONNECTION_TIER_ADMIN_PORT", "8091"));

        DocumentMetadataServer server = new DocumentMetadataServer(
                PORT,
                new PostgresDocumentMetadataStore(dataSource),
                new PostgresDocumentRosterStore(dataSource),
                new DocumentServiceDeleteClient(documentServiceHost, documentServicePort),
                new ConnectionTierEvictClient(connectionTierHost, connectionTierPort));
        server.start();
    }
}
