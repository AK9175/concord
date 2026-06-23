package com.collabdoc.document;

import com.collabdoc.ot.InsertOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryOperationLogTest {

    @Test
    void appendAssignsContiguousIncreasingRevisions() {
        OperationLog log = new InMemoryOperationLog();
        CommittedOperation first = log.append("doc-1", new InsertOperation(0, "alice", 0, "a"));
        CommittedOperation second = log.append("doc-1", new InsertOperation(0, "alice", 1, "b"));

        assertEquals(1, first.revision());
        assertEquals(2, second.revision());
        assertEquals(2, log.currentRevision("doc-1"));
    }

    @Test
    void readFromReturnsOnlyOpsAfterGivenRevisionInOrder() {
        OperationLog log = new InMemoryOperationLog();
        log.append("doc-1", new InsertOperation(0, "alice", 0, "a"));
        log.append("doc-1", new InsertOperation(0, "alice", 1, "b"));
        log.append("doc-1", new InsertOperation(0, "alice", 2, "c"));

        List<CommittedOperation> gap = log.readFrom("doc-1", 1);

        assertEquals(2, gap.size());
        assertEquals(2, gap.get(0).revision());
        assertEquals(3, gap.get(1).revision());
    }

    @Test
    void unknownDocumentHasZeroRevisionAndEmptyLog() {
        OperationLog log = new InMemoryOperationLog();
        assertEquals(0, log.currentRevision("nonexistent"));
        assertTrue(log.readFrom("nonexistent", 0).isEmpty());
    }

    @Test
    void differentDocumentsHaveIndependentLogs() {
        OperationLog log = new InMemoryOperationLog();
        log.append("doc-A", new InsertOperation(0, "alice", 0, "a"));
        log.append("doc-B", new InsertOperation(0, "bob", 0, "b"));
        log.append("doc-A", new InsertOperation(0, "alice", 1, "a2"));

        assertEquals(2, log.currentRevision("doc-A"));
        assertEquals(1, log.currentRevision("doc-B"));
    }
}
