package com.collabdoc.document;

import com.collabdoc.ot.Operation;
import com.collabdoc.ot.OperationApplier;
import com.collabdoc.ot.OperationTransformer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The actual commit pipeline for one incoming operation: transform it
 * against whatever landed in the revision gap since it was created, apply
 * the result to the document's in-memory text, and append it to the log.
 *
 * commit() is NOT safe to call concurrently for the same documentId -- like
 * OperationLog.append(), it depends on DocumentSequencer's per-document
 * single-threaded executor to guarantee it's never invoked twice at once for
 * the same document. That's what makes "read current gap, then apply" safe
 * without a lock here: there's only ever one thread that could be racing
 * with itself, and there isn't one.
 */
class DocumentCommitter {

    private final OperationLog operationLog;
    private final Map<String, String> textByDocument = new ConcurrentHashMap<>();

    DocumentCommitter(OperationLog operationLog) {
        this.operationLog = operationLog;
    }

    List<CommittedOperation> commit(String documentId, Operation incoming) {
        List<CommittedOperation> gap = operationLog.readFrom(documentId, incoming.baseRevision());

        List<Operation> pieces = new ArrayList<>(List.of(incoming));
        for (CommittedOperation alreadyCommitted : gap) {
            List<Operation> next = new ArrayList<>();
            for (Operation piece : pieces) {
                next.addAll(OperationTransformer.transform(piece, alreadyCommitted.operation()));
            }
            pieces = next;
        }

        // Same descending-position ordering proven correct in ot-core's CP 1.5
        // property test: pieces from a single transform share one coordinate
        // frame, not a sequential chain, so the higher-positioned one must apply
        // first or the lower one's position would be invalidated underneath it.
        List<Operation> inApplyOrder = pieces.stream()
                .sorted(Comparator.comparingInt(Operation::position).reversed())
                .toList();

        String currentText = currentText(documentId);
        String updatedText = OperationApplier.applyAll(currentText, inApplyOrder);
        textByDocument.put(documentId, updatedText);

        // A single incoming op can become more than one log entry here (the
        // insert-survives-delete split from ot-core) -- each piece gets its own
        // revision, assigned in application order, since OperationLog.append()
        // assigns exactly one revision per call.
        List<CommittedOperation> committed = new ArrayList<>();
        for (Operation piece : inApplyOrder) {
            committed.add(operationLog.append(documentId, piece));
        }
        return committed;
    }

    /**
     * CP 5.3: the in-memory cache starts empty on every server restart, but
     * Postgres may already hold a long history for documentId from before
     * that restart. Defaulting to "" for any uncached document (the
     * pre-CP-5.3 behavior) would silently apply the FIRST edit after a
     * restart to an empty string instead of the document's real prior
     * content -- the log itself stays correct (append() doesn't care about
     * this cache), but the server's working copy, and everything built on
     * top of it from that point on, would be wrong. computeIfAbsent's
     * per-key atomicity (guaranteed by ConcurrentHashMap) makes this safe to
     * call from commit() (always on this document's single-threaded
     * executor) and from direct external callers (e.g. tests) concurrently:
     * the rebuild is a pure, idempotent function of the log, so even a
     * hypothetical double-computation would just redo the same work twice,
     * never produce a wrong or inconsistent result.
     */
    String currentText(String documentId) {
        return textByDocument.computeIfAbsent(documentId, this::rebuildFromLog);
    }

    private String rebuildFromLog(String documentId) {
        String text = "";
        for (CommittedOperation committed : operationLog.readFrom(documentId, 0)) {
            text = OperationApplier.apply(text, committed.operation());
        }
        return text;
    }

    /** Every committed operation for documentId, in revision order, from the start. */
    List<CommittedOperation> history(String documentId) {
        return operationLog.readFrom(documentId, 0);
    }
}
