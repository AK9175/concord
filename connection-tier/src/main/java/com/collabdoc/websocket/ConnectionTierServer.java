package com.collabdoc.websocket;

import com.collabdoc.document.CommittedOperation;
import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.ot.Operation;
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
 * Each connection is scoped to exactly one document, taken from the request
 * path (e.g. ws://host:port/doc-1 -> documentId "doc-1") at connect time --
 * mirroring how Phase 3's browser client opens one document per page.
 *
 * Talks to DocumentSequencer with a direct, in-process Java call (see the
 * tradeoff note in this module's pom.xml): there is no document-service
 * process boundary until Phase 6.
 */
public class ConnectionTierServer extends WebSocketServer {

    private final DocumentSequencer sequencer;
    private final DocumentSessionRegistry sessions = new DocumentSessionRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConnectionTierServer(int port, DocumentSequencer sequencer) {
        super(new InetSocketAddress(port));
        this.sequencer = sequencer;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String documentId = documentIdFromPath(handshake.getResourceDescriptor());
        System.out.println("connection-tier: connected " + conn.getRemoteSocketAddress() + " to " + documentId);

        // Registering as a broadcast peer and reading history happen atomically
        // on the document's own executor (see DocumentSequencer.connect) so no
        // concurrent commit can land in the gap between them -- that would
        // otherwise risk delivering it to this client twice, or not at all.
        sequencer.connect(documentId, () -> sessions.join(documentId, conn))
                .thenAccept(history -> send(conn, ServerMessage.history(documentId, history)));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        sessions.leave(conn);
        System.out.println("connection-tier: closed " + conn.getRemoteSocketAddress() + " (" + reason + ")");
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            String documentId = sessions.documentIdFor(conn);
            Operation operation = objectMapper.readValue(message, Operation.class);

            sequencer.submit(documentId, operation)
                    .thenAccept(committed -> deliver(conn, documentId, committed));
        } catch (Exception ex) {
            // Malformed input from a client is a boundary condition, not a server
            // bug -- log and ignore rather than letting one bad message take the
            // connection (or the server) down.
            ex.printStackTrace();
        }
    }

    private void deliver(WebSocket originator, String documentId, List<CommittedOperation> committed) {
        send(originator, ServerMessage.ack(documentId, committed));
        ServerMessage update = ServerMessage.update(documentId, committed);
        for (WebSocket peer : sessions.othersInDocument(documentId, originator)) {
            send(peer, update);
        }
    }

    private void send(WebSocket connection, ServerMessage message) {
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
