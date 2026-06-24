package com.collabdoc.document;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.Operation;

import java.util.List;

/**
 * Append-only, per-document log of committed operations, ordered by
 * revision. In-memory for now (InMemoryOperationLog); the interface is the
 * seam Phase 5 (CP 5.1) swaps a Postgres-backed implementation behind, with
 * no change to the sequencer logic that depends on it.
 */
public interface OperationLog {

    /**
     * Appends operation as the next revision for documentId and returns the
     * committed record. Callers must guarantee this is never invoked
     * concurrently for the same documentId -- revision assignment reads
     * "current" and writes "current+1", which is only safe under
     * single-writer-at-a-time access (see DocumentSequencer, which provides
     * that guarantee).
     */
    CommittedOperation append(String documentId, Operation operation);

    /** Committed operations for documentId with revision strictly greater than fromRevision, ordered ascending. */
    List<CommittedOperation> readFrom(String documentId, long fromRevision);

    /** The latest committed revision for documentId, or 0 if nothing has been committed yet. */
    long currentRevision(String documentId);

    /** Permanently removes every committed operation for documentId. A no-op if documentId has none. */
    void delete(String documentId);
}
