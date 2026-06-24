package com.collabdoc.websocket;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Phase 6 replacement for the atomicity DocumentSequencer.connect() used to
 * give connection-tier for free: registering as a broadcast peer and
 * reading history happening as one atomic step on document-service's own
 * per-document executor, so no commit could land in the gap between them.
 *
 * Once document-service is a separate process, it has no idea connection-
 * tier's peer list even exists -- so connection-tier now has to serialize
 * "connect" (join peer + fetch history) against "submit + broadcast" itself,
 * per document, the same way DocumentSequencer always serialized commits.
 *
 * Each queued task blocks its document's single executor thread until its
 * OWN gRPC round trip fully completes (task.get() returns a pending future;
 * calling .get() on THAT blocks the executor thread until the network
 * response actually arrives) -- not just until the call is kicked off.
 * Without that, a second task for the same document could start before the
 * first one's response (and the peer-list/broadcast side effect that
 * depends on it) has landed, reopening exactly the race this class exists
 * to close. This mirrors document-service's own per-document executor
 * design (CP 2.2): one parked OS thread per active document is an accepted,
 * pre-existing cost, not a new one introduced here.
 *
 * Callers attaching .thenAccept(...) to the returned future (e.g. to
 * broadcast a commit's result) get that callback run SYNCHRONOUSLY on this
 * same executor thread, by ordinary CompletableFuture semantics -- a
 * dependent stage attached before completion runs on whichever thread calls
 * complete(), unless an Executor is explicitly given. That's what keeps the
 * broadcast itself inside the same atomic unit as the network call, with no
 * extra plumbing: don't change callers to .thenAcceptAsync(...) without
 * re-deriving this.
 */
class DocumentTaskSequencer {

    private final Map<String, ExecutorService> executorsByDocument = new ConcurrentHashMap<>();

    <T> CompletableFuture<T> runOnDocumentExecutor(String documentId, Supplier<CompletableFuture<T>> task) {
        ExecutorService executor = executorsByDocument.computeIfAbsent(
                documentId, id -> Executors.newSingleThreadExecutor());
        CompletableFuture<T> result = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                result.complete(task.get().get());
            } catch (ExecutionException ex) {
                result.completeExceptionally(ex.getCause());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(ex);
            }
        });
        return result;
    }
}
