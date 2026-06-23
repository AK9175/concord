package com.collabdoc.document;

import com.collabdoc.ot.DeleteOperation;
import com.collabdoc.ot.InsertOperation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every OperationLog implementation must satisfy, run against
 * BOTH InMemoryOperationLog and (CP 5.1) PostgresOperationLog. This IS "the
 * existing Phase 2 tests pass unchanged against the Postgres-backed log" --
 * not a separate copy of similar assertions for the new implementation.
 */
abstract class OperationLogContractTest {

    protected abstract OperationLog createLog();

    private static String freshDocumentId() {
        return "doc-" + UUID.randomUUID();
    }

    @Test
    void appendAssignsContiguousIncreasingRevisions() {
        OperationLog log = createLog();
        String documentId = freshDocumentId();

        CommittedOperation first = log.append(documentId, new InsertOperation(0, "alice", 0, "a"));
        CommittedOperation second = log.append(documentId, new InsertOperation(0, "alice", 1, "b"));

        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
        assertEquals(2, log.currentRevision(documentId));
    }

    @Test
    void readFromReturnsOnlyOpsAfterGivenRevisionInOrder() {
        OperationLog log = createLog();
        String documentId = freshDocumentId();

        log.append(documentId, new InsertOperation(0, "alice", 0, "a"));
        log.append(documentId, new InsertOperation(0, "alice", 1, "b"));
        log.append(documentId, new InsertOperation(0, "alice", 2, "c"));

        List<CommittedOperation> gap = log.readFrom(documentId, 1);

        assertEquals(2, gap.size());
        assertEquals(2, gap.get(0).revision());
        assertEquals(3, gap.get(1).revision());
    }

    @Test
    void unknownDocumentHasZeroRevisionAndEmptyLog() {
        OperationLog log = createLog();
        String documentId = freshDocumentId();

        assertEquals(0, log.currentRevision(documentId));
        assertTrue(log.readFrom(documentId, 0).isEmpty());
    }

    @Test
    void differentDocumentsHaveIndependentLogs() {
        OperationLog log = createLog();
        String docA = freshDocumentId();
        String docB = freshDocumentId();

        log.append(docA, new InsertOperation(0, "alice", 0, "a"));
        log.append(docB, new InsertOperation(0, "bob", 0, "b"));
        log.append(docA, new InsertOperation(0, "alice", 1, "a2"));

        assertEquals(2, log.currentRevision(docA));
        assertEquals(1, log.currentRevision(docB));
    }

    @Test
    void roundTripsInsertAndDeleteOperationsFaithfully() {
        OperationLog log = createLog();
        String documentId = freshDocumentId();

        log.append(documentId, new InsertOperation(5, "alice", 0, "hello"));
        log.append(documentId, new DeleteOperation(6, "bob", 1, 3));

        List<CommittedOperation> history = log.readFrom(documentId, 0);

        assertEquals(2, history.size());
        InsertOperation insert = (InsertOperation) history.get(0).operation();
        assertEquals(5, insert.baseRevision());
        assertEquals("alice", insert.userId());
        assertEquals(0, insert.position());
        assertEquals("hello", insert.text());

        DeleteOperation delete = (DeleteOperation) history.get(1).operation();
        assertEquals(6, delete.baseRevision());
        assertEquals("bob", delete.userId());
        assertEquals(1, delete.position());
        assertEquals(3, delete.length());
    }

    @Test
    void rowsComeBackInRevisionOrder() {
        OperationLog log = createLog();
        String documentId = freshDocumentId();

        for (int i = 0; i < 20; i++) {
            log.append(documentId, new InsertOperation(0, "alice", 0, "x" + i));
        }

        List<CommittedOperation> history = log.readFrom(documentId, 0);
        for (int i = 0; i < history.size(); i++) {
            assertEquals(i + 1, history.get(i).revision());
        }
    }
}
