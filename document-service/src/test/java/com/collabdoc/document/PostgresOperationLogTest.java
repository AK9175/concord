package com.collabdoc.document;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the same OperationLogContractTest suite against a real Postgres
 * instance. Skips (not fails) if Postgres isn't reachable -- see
 * docker-compose.yml at the repo root ("docker compose up -d") for an
 * identical local instance.
 */
class PostgresOperationLogTest extends OperationLogContractTest {

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
    protected OperationLog createLog() {
        return new PostgresOperationLog(dataSource);
    }
}
