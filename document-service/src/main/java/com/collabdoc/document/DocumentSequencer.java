package com.collabdoc.document;

import com.collabdoc.ot.Operation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The single authoritative sequencer per document (project invariant #1):
 * every operation for a given documentId is processed by one dedicated,
 * single-threaded executor, so two ops for the SAME document are never
 * mid-commit at once no matter how many threads call submit() concurrently.
 * Different documents get independent executors and proceed fully in
 * parallel -- this is what lets one document-service instance hold many
 * documents without them contending with each other.
 *
 * The actual gap-transform/apply/append pipeline lives in DocumentCommitter;
 * this class only owns the per-document thread that makes calling it safe.
 */
public class DocumentSequencer {

    private final DocumentCommitter committer;
    private final Map<String, ExecutorService> executorsByDocument = new ConcurrentHashMap<>();

    public DocumentSequencer(OperationLog operationLog) {
        this.committer = new DocumentCommitter(operationLog);
    }

    public CompletableFuture<List<CommittedOperation>> submit(String documentId, Operation operation) {
        return runOnDocumentExecutor(documentId, () -> committer.commit(documentId, operation));
    }

    /**
     * Registers a newly-connecting client as eligible to receive future
     * broadcasts (via registerAsPeer) and returns the document's full history,
     * as ONE atomic step on the document's own executor -- the same one every
     * commit() runs on. That ordering is what prevents two races: a commit
     * landing between "registered as peer" and "history read" (which would
     * deliver that op to the new client twice -- once in history, once via
     * broadcast), and a commit landing between "history read" and
     * "registered as peer" (which would deliver it to the new client neither
     * way, a silent gap). Because registerAsPeer.run() and history() both
     * happen inside this one executor task, no commit for this document can
     * be interleaved between them.
     */
    public CompletableFuture<List<CommittedOperation>> connect(String documentId, Runnable registerAsPeer) {
        return runOnDocumentExecutor(documentId, () -> {
            registerAsPeer.run();
            return committer.history(documentId);
        });
    }

    /** The document's current text, reflecting every commit so far. Empty string if untouched. */
    public String currentText(String documentId) {
        return committer.currentText(documentId);
    }

    private <T> CompletableFuture<T> runOnDocumentExecutor(String documentId, Supplier<T> task) {
        ExecutorService executor = executorsByDocument.computeIfAbsent(
                documentId, id -> Executors.newSingleThreadExecutor());
        return CompletableFuture.supplyAsync(task, executor);
    }
}
