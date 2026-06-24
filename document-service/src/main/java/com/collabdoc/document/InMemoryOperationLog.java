package com.collabdoc.document;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory OperationLog. Per CP 5.1 this gets swapped for a Postgres-backed
 * implementation later with no change to callers.
 *
 * append() is NOT safe to call concurrently for the same documentId on its
 * own -- revision assignment here is just "current size + 1", which races if
 * two threads append for the same document at once. That single-writer
 * guarantee is DocumentSequencer's job (one executor per document), not
 * this class's. CopyOnWriteArrayList is only here so concurrent *readers*
 * (replay requests) stay safe while the sequencer's one writer thread is
 * appending, not to make concurrent writers safe.
 */
public class InMemoryOperationLog implements OperationLog {

    private final Map<String, CopyOnWriteArrayList<CommittedOperation>> logsByDocument = new ConcurrentHashMap<>();

    @Override
    public CommittedOperation append(String documentId, Operation operation) {
        CopyOnWriteArrayList<CommittedOperation> log =
                logsByDocument.computeIfAbsent(documentId, id -> new CopyOnWriteArrayList<>());
        long nextRevision = log.size() + 1L;
        CommittedOperation committed = new CommittedOperation(nextRevision, operation);
        log.add(committed);
        return committed;
    }

    @Override
    public List<CommittedOperation> readFrom(String documentId, long fromRevision) {
        CopyOnWriteArrayList<CommittedOperation> log = logsByDocument.get(documentId);
        if (log == null) {
            return List.of();
        }
        List<CommittedOperation> result = new ArrayList<>();
        for (CommittedOperation committed : log) {
            if (committed.revision() > fromRevision) {
                result.add(committed);
            }
        }
        return result;
    }

    @Override
    public long currentRevision(String documentId) {
        CopyOnWriteArrayList<CommittedOperation> log = logsByDocument.get(documentId);
        return log == null ? 0L : log.size();
    }
}
