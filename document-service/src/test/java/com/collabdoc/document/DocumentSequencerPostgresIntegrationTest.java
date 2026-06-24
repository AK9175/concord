package com.collabdoc.document;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.InsertOperation;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CP 5.2's literal verification (durable-write half): a committed+acked op
 * is actually present in Postgres -- proven end to end through
 * DocumentSequencer, not just by testing PostgresOperationLog in isolation
 * (OperationLogContractTest already covers that). This confirms the WIRING
 * between the sequencer and the real database is correct too.
 */
class DocumentSequencerPostgresIntegrationTest {

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

    @Test
    void aCommittedOperationIsDurablyVisibleInPostgresIndependentlyOfTheSequencer() throws Exception {
        DocumentSequencer sequencer = new DocumentSequencer(new PostgresOperationLog(dataSource));
        String documentId = "doc-" + UUID.randomUUID();

        List<CommittedOperation> committed = sequencer.submit(documentId,
                new InsertOperation(0, "alice", 0, "hello")).join();

        assertEquals(1, committed.size());
        long revision = committed.get(0).revision();

        // Query Postgres directly, completely independent of DocumentSequencer/
        // PostgresOperationLog's own read methods, to prove the write really
        // landed durably -- not just that our own read path reports it did.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT user_id, op_type, op_data FROM operations WHERE document_id = ? AND revision = ?")) {
            stmt.setString(1, documentId);
            stmt.setLong(2, revision);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "expected a row for the committed operation");
                assertEquals("alice", rs.getString("user_id"));
                assertEquals("insert", rs.getString("op_type"));
                assertTrue(rs.getString("op_data").contains("hello"));
            }
        }
    }
}
