package com.collabdoc.document;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.InsertOperation;
import com.collabdoc.ot.OperationApplier;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CP 5.3's literal verification (the actual durability proof): make several
 * edits, stop and restart the server, reopen the doc -- all edits are
 * present, and a fresh client loads the identical document.
 *
 * "Restart" is simulated by constructing a BRAND NEW DocumentSequencer (and
 * therefore a brand new, empty DocumentCommitter cache) pointed at the SAME
 * underlying Postgres data -- exactly what actually happens when the real
 * server process restarts: the JVM and all its in-memory state are gone,
 * but the database isn't.
 */
class DocumentSequencerColdStartTest {

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
    void allEditsSurviveARestartAndFurtherEditsBuildOnTheRealPriorContent() {
        String documentId = "doc-" + UUID.randomUUID();

        // "Before restart": make several edits with one DocumentSequencer instance.
        DocumentSequencer beforeRestart = new DocumentSequencer(new PostgresOperationLog(dataSource));
        beforeRestart.submit(documentId, new InsertOperation(0, "alice", 0, "Hello")).join();
        beforeRestart.submit(documentId, new InsertOperation(1, "alice", 5, " world")).join();
        assertEquals("Hello world", beforeRestart.currentText(documentId));

        // "Restart": a brand new DocumentSequencer, brand new (empty) in-memory
        // cache, same Postgres data. Nothing carries over from the old instance.
        DocumentSequencer afterRestart = new DocumentSequencer(new PostgresOperationLog(dataSource));

        // A fresh client connecting (history replay) sees the identical document.
        List<CommittedOperation> history = afterRestart.history(documentId).join();
        String replayed = "";
        for (CommittedOperation committed : history) {
            replayed = OperationApplier.apply(replayed, committed.operation());
        }
        assertEquals("Hello world", replayed);

        // currentText() also reports the real prior content, not "" -- this is
        // the specific bug CP 5.3 fixes: without rebuilding from the log, this
        // would have reported "" since this DocumentSequencer never saw the
        // pre-restart edits itself.
        assertEquals("Hello world", afterRestart.currentText(documentId));

        // The critical case: an edit made AFTER the restart must apply against
        // the REAL prior content, not against an empty string. Before the
        // fix, this would corrupt the document (insert at position 11 into ""
        // would even throw -- "" has no position 11).
        afterRestart.submit(documentId, new InsertOperation(2, "bob", 11, "!")).join();
        assertEquals("Hello world!", afterRestart.currentText(documentId));
    }
}
