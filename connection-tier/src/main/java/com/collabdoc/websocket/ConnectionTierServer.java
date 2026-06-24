package com.collabdoc.websocket;

import com.collabdoc.ot.CommittedOperation;
import com.collabdoc.ot.Operation;
import com.collabdoc.websocket.grpc.DocumentServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * CP 2.4: on a committed edit, ack the originator and broadcast the
 * (possibly transformed) result to every other connection on the same
 * document -- never echo an op back to whoever sent it.
 *
 * CP 2.5: on connect, send the new client the full op log so it can
 * reconstruct current state by replaying it (no snapshots).
 *
 * CP 3.6: also accepts a "resync" control message (not an Operation) that
 * just re-sends history -- a client-side correctness safety net, not a new
 * conflict-resolution mechanism.
 *
 * CP 4.3: also accepts a "presence" announcement (userId/username/color,
 * sourced from the document's roster, which only the client has fetched --
 * this is the entire connection-tier<->roster "bridge", a one-line message
 * forwarded over a socket that's already open, not a new service-to-service
 * call). Presence is tracked by PresenceRegistry, keyed by userId so
 * multiple tabs under the same identity collapse into one logical presence.
 *
 * CP 4.4: also accepts "updateCursor" (a caret position), broadcasting a
 * "cursor-update" to everyone else on the document. No transformation
 * against concurrent edits is attempted -- the position is whatever the
 * sender's own textarea selectionStart was, re-rendered by each recipient
 * against its own current text; a brief, self-correcting visual lag right
 * after a concurrent edit is an accepted simplification, not a defect.
 *
 * Each connection is scoped to exactly one document, taken from the request
 * path (e.g. ws://host:port/doc-1 -> documentId "doc-1") at connect time --
 * mirroring how Phase 3's browser client opens one document per page.
 *
 * Phase 6: talks to document-service over gRPC (DocumentServiceClient),
 * a genuinely separate process now, not an in-process Java call. The
 * register-as-peer + atomic-history-read guarantee that
 * DocumentSequencer.connect() used to provide is now connection-tier's own
 * responsibility -- see DocumentTaskSequencer.
 */
public class ConnectionTierServer extends WebSocketServer {

    private final DocumentServiceClient documentService;
    private final DocumentSessionRegistry sessions = new DocumentSessionRegistry();
    private final PresenceRegistry presence = new PresenceRegistry();
    private final DocumentTaskSequencer taskSequencer = new DocumentTaskSequencer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConnectionTierServer(int port, DocumentServiceClient documentService) {
        super(new InetSocketAddress(port));
        this.documentService = documentService;
    }

    /** Lets Main wire ConnectionTierAdminServer to the SAME session registry this server uses, not a separate one. */
    DocumentSessionRegistry sessions() {
        return sessions;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String documentId = documentIdFromPath(handshake.getResourceDescriptor());
        System.out.println("connection-tier: connected " + conn.getRemoteSocketAddress() + " to " + documentId);

        // Registering as a broadcast peer and reading history happen atomically
        // on this document's own task-sequencer executor (see
        // DocumentTaskSequencer) so no concurrent commit can land in the gap
        // between them -- that would otherwise risk delivering it to this
        // client twice, or not at all.
        taskSequencer.runOnDocumentExecutor(documentId, () -> {
                    sessions.join(documentId, conn);
                    return documentService.history(documentId);
                })
                .thenAccept(history -> send(conn, ServerMessage.history(documentId, history)))
                .exceptionally(this::logAsyncFailure);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        sessions.leave(conn);
        presence.leave(conn).ifPresent(key -> {
            for (WebSocket peer : sessions.othersInDocument(key.documentId(), conn)) {
                send(peer, PresenceMessage.leave(key.documentId(), key.userId()));
            }
        });
        System.out.println("connection-tier: closed " + conn.getRemoteSocketAddress() + " (" + reason + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            String documentId = sessions.documentIdFor(conn);
            JsonNode root = objectMapper.readTree(message);
            String type = root.path("type").asText();

            if ("resync".equals(type)) {
                // A client-side correctness safety net (see ot-client.js): when a
                // client's local optimistic reconciliation might have drifted (the
                // multi-pending-op TP2-style edge case), it asks for history again
                // rather than trusting its own incremental state indefinitely. This
                // is a plain read, not tied to peer registration -- the connection is
                // already registered from onOpen.
                documentService.history(documentId)
                        .thenAccept(history -> send(conn, ServerMessage.history(documentId, history)))
                        .exceptionally(this::logAsyncFailure);
                return;
            }

            if ("presence".equals(type)) {
                handlePresenceAnnouncement(conn, documentId, root);
                return;
            }

            if ("updateCursor".equals(type)) {
                int position = root.path("position").asInt();
                presence.updateCursor(conn, position).ifPresent(key -> {
                    PresenceMessage cursorMessage = PresenceMessage.cursorUpdate(key.documentId(), key.userId(), position);
                    for (WebSocket peer : sessions.othersInDocument(key.documentId(), conn)) {
                        send(peer, cursorMessage);
                    }
                });
                return;
            }

            Operation operation = objectMapper.treeToValue(root, Operation.class);
            // Submitting and broadcasting are sequenced through the same
            // per-document executor as connect() (see DocumentTaskSequencer):
            // .thenAccept here runs synchronously on that executor thread by
            // ordinary CompletableFuture completion semantics, keeping the
            // broadcast inside the same atomic unit as the network call.
            taskSequencer.runOnDocumentExecutor(documentId, () -> documentService.submit(documentId, operation))
                    .thenAccept(committed -> deliver(conn, documentId, committed))
                    .exceptionally(this::logAsyncFailure);
        } catch (Exception ex) {
            // Malformed input from a client is a boundary condition, not a server
            // bug -- log and ignore rather than letting one bad message take the
            // connection (or the server) down.
            ex.printStackTrace();
        }
    }

    private void handlePresenceAnnouncement(WebSocket conn, String documentId, JsonNode root) {
        String userId = root.path("userId").asText();
        String username = root.path("username").asText();
        String color = root.path("color").asText();

        boolean isFirstConnectionForUser = presence.join(documentId, userId, username, color, conn);
        if (isFirstConnectionForUser) {
            PresenceMessage joinMessage = PresenceMessage.join(documentId, userId, username, color);
            for (WebSocket peer : sessions.othersInDocument(documentId, conn)) {
                send(peer, joinMessage);
            }
        }

        List<PresenceUser> others = presence.snapshot(documentId).stream()
                .filter(entry -> !entry.userId().equals(userId))
                .map(entry -> new PresenceUser(entry.userId(), entry.username(), entry.color(), entry.cursorPosition()))
                .toList();
        send(conn, PresenceMessage.snapshot(documentId, others));
    }

    private void deliver(WebSocket originator, String documentId, List<CommittedOperation> committed) {
        send(originator, ServerMessage.ack(documentId, committed));
        ServerMessage update = ServerMessage.update(documentId, committed);
        for (WebSocket peer : sessions.othersInDocument(documentId, originator)) {
            send(peer, update);
        }
    }

    /**
     * Without this, an exception thrown inside a thenAccept callback (e.g.
     * commit() rejecting an op) just vanishes -- the resulting
     * CompletableFuture completes exceptionally, but nothing was ever
     * observing it for errors, so it fails completely silently: no log line,
     * no ack, nothing. That silence is exactly what let a real client-side
     * bug (CP 4.4 baseRevision staleness) masquerade as "edits just stop
     * syncing" instead of an obvious stack trace.
     */
    private Void logAsyncFailure(Throwable ex) {
        ex.printStackTrace();
        return null;
    }

    private void send(WebSocket connection, Object message) {
        try {
            connection.send(objectMapper.writeValueAsString(message));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static String documentIdFromPath(String resourceDescriptor) {
        String path = resourceDescriptor.startsWith("/") ? resourceDescriptor.substring(1) : resourceDescriptor;
        int queryIndex = path.indexOf('?');
        return queryIndex >= 0 ? path.substring(0, queryIndex) : path;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("connection-tier: listening on port " + getPort());
    }
}
