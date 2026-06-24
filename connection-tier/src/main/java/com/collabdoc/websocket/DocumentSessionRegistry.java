package com.collabdoc.websocket;

import org.java_websocket.WebSocket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks which connections are currently editing which document, so a
 * commit can be broadcast to "everyone else on this document" and skip the
 * originator. A connection joins its document at connect time (the
 * documentId comes from the WebSocket request path, e.g. "/doc-1") rather
 * than from message content -- this matches how Phase 3's browser client
 * will open exactly one document per connection.
 */
class DocumentSessionRegistry {

    private final Map<String, Set<WebSocket>> connectionsByDocument = new ConcurrentHashMap<>();
    private final Map<WebSocket, String> documentByConnection = new ConcurrentHashMap<>();

    void join(String documentId, WebSocket connection) {
        connectionsByDocument.computeIfAbsent(documentId, id -> ConcurrentHashMap.newKeySet()).add(connection);
        documentByConnection.put(connection, documentId);
    }

    void leave(WebSocket connection) {
        String documentId = documentByConnection.remove(connection);
        if (documentId == null) {
            return;
        }
        Set<WebSocket> peers = connectionsByDocument.get(documentId);
        if (peers != null) {
            peers.remove(connection);
        }
    }

    Set<WebSocket> othersInDocument(String documentId, WebSocket excluding) {
        Set<WebSocket> peers = connectionsByDocument.getOrDefault(documentId, Set.of());
        return peers.stream().filter(connection -> connection != excluding).collect(Collectors.toSet());
    }

    /** Every connection currently on documentId, including all of them -- used for eviction, not broadcast. */
    Set<WebSocket> allInDocument(String documentId) {
        return Set.copyOf(connectionsByDocument.getOrDefault(documentId, Set.of()));
    }

    String documentIdFor(WebSocket connection) {
        return documentByConnection.get(connection);
    }
}
