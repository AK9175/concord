package com.collabdoc.metadata;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Each contract test uses a fresh random documentId (see
 * DocumentRosterStoreContractTest.freshDocumentId), so unlike
 * PostgresDocumentMetadataStoreTest, no per-test table truncation is needed
 * -- listUsers() is already scoped to one documentId, and tests never
 * overlap on the same one.
 */
class PostgresDocumentRosterStoreTest extends DocumentRosterStoreContractTest {

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

    @Override
    protected DocumentRosterStore createStore() {
        return new PostgresDocumentRosterStore(dataSource);
    }
}
