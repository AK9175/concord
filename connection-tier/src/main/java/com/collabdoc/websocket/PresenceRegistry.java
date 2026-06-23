package com.collabdoc.websocket;

import org.java_websocket.WebSocket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral, in-memory presence keyed by userId (not by connection): opening
 * a second tab under the same identity must not create a second presence
 * entry for everyone else. Tracks a per-user connection count so a user is
 * only removed (and a departure broadcast) once their LAST connection
 * drops. Never persisted, never touches DocumentRosterStore (a separate,
 * persistent concern in a different module/process) -- losing all presence
 * on restart is fine and expected, per the project's identity model.
 *
 * cursorPosition (CP 4.4) follows "their most-recently-active connection":
 * whichever connection most recently called updateCursor() simply
 * overwrites it, regardless of which of a user's tabs sent it.
 */
class PresenceRegistry {

    record Entry(String userId, String username, String color, int connectionCount, Integer cursorPosition) {
    }

    record UserKey(String documentId, String userId) {
    }

    private final Map<String, Map<String, Entry>> entriesByDocument = new ConcurrentHashMap<>();
    private final Map<WebSocket, UserKey> userKeyByConnection = new ConcurrentHashMap<>();

    /** Returns true if this connection is this user's FIRST active connection on this document. */
    synchronized boolean join(String documentId, String userId, String username, String color, WebSocket connection) {
        userKeyByConnection.put(connection, new UserKey(documentId, userId));
        Map<String, Entry> entries = entriesByDocument.computeIfAbsent(documentId, id -> new ConcurrentHashMap<>());
        Entry existing = entries.get(userId);
        if (existing == null) {
            entries.put(userId, new Entry(userId, username, color, 1, null));
            return true;
        }
        // A second tab under the same identity: bump the connection count, but keep
        // whatever cursor position is already on record rather than resetting it.
        entries.put(userId, new Entry(userId, username, color, existing.connectionCount() + 1, existing.cursorPosition()));
        return false;
    }

    /** Returns the (documentId, userId) if this was that user's LAST active connection (now fully gone). */
    synchronized Optional<UserKey> leave(WebSocket connection) {
        UserKey key = userKeyByConnection.remove(connection);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Entry> entries = entriesByDocument.get(key.documentId());
        if (entries == null) {
            return Optional.empty();
        }
        Entry existing = entries.get(key.userId());
        if (existing == null) {
            return Optional.empty();
        }
        if (existing.connectionCount() <= 1) {
            entries.remove(key.userId());
            return Optional.of(key);
        }
        entries.put(key.userId(), new Entry(existing.userId(), existing.username(), existing.color(),
                existing.connectionCount() - 1, existing.cursorPosition()));
        return Optional.empty();
    }

    /** Records a cursor position for whichever user this connection announced itself as. */
    synchronized Optional<UserKey> updateCursor(WebSocket connection, int position) {
        UserKey key = userKeyByConnection.get(connection);
        if (key == null) {
            return Optional.empty();
        }
        Map<String, Entry> entries = entriesByDocument.get(key.documentId());
        if (entries == null) {
            return Optional.empty();
        }
        Entry existing = entries.get(key.userId());
        if (existing == null) {
            return Optional.empty();
        }
        entries.put(key.userId(), new Entry(existing.userId(), existing.username(), existing.color(),
                existing.connectionCount(), position));
        return Optional.of(key);
    }

    /** Everyone currently present on documentId, one entry per userId regardless of how many tabs they have open. */
    List<Entry> snapshot(String documentId) {
        Map<String, Entry> entries = entriesByDocument.get(documentId);
        return entries == null ? List.of() : new ArrayList<>(entries.values());
    }
}
