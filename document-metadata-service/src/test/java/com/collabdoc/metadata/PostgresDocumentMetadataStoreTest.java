package com.collabdoc.metadata;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresDocumentMetadataStoreTest extends DocumentMetadataStoreContractTest {

    private static HikariDataSource dataSource;

    @BeforeAll
    static void connectOrSkip() {
        HikariDataSource candidate = PostgresDataSources.create(
                "jdbc:postgresql://localhost:5432/concord", "concord", "concord");
        boolean reachable;
        try (Connection conn = candidate.getConnection()) {
            reachable = conn.isValid(2);
        } catch (SQLException ex) {
            reachable = false;
        }
        assumeTrue(reachable, "Postgres not reachable at localhost:5432 -- run `docker compose up -d` first");
        dataSource = candidate;
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void truncateTable() throws SQLException {
        new PostgresDocumentMetadataStore(dataSource); // ensures the table exists before truncating it
        try (Connection conn = dataSource.getConnection();
             var statement = conn.createStatement()) {
            statement.execute("TRUNCATE TABLE documents");
        }
    }

    @Override
    protected DocumentMetadataStore createStore() {
        return new PostgresDocumentMetadataStore(dataSource);
    }
}
