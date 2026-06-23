package com.collabdoc.websocket;

import java.util.List;

/**
 * Wire format for presence-related outbound messages: "presence-snapshot"
 * (sent once, to a newly-announcing connection, listing everyone else
 * currently present), "presence-join" (a user's first connection arrived),
 * "presence-leave" (a user's last connection dropped), and "cursor-update"
 * (CP 4.4 -- a user's caret moved). Separate from ServerMessage since these
 * never carry a CommittedOperation.
 */
public record PresenceMessage(String type, String documentId, String userId, String username, String color,
                               Integer position, List<PresenceUser> users) {

    public static PresenceMessage join(String documentId, String userId, String username, String color) {
        return new PresenceMessage("presence-join", documentId, userId, username, color, null, null);
    }

    public static PresenceMessage leave(String documentId, String userId) {
        return new PresenceMessage("presence-leave", documentId, userId, null, null, null, null);
    }

    public static PresenceMessage snapshot(String documentId, List<PresenceUser> users) {
        return new PresenceMessage("presence-snapshot", documentId, null, null, null, null, users);
    }

    public static PresenceMessage cursorUpdate(String documentId, String userId, int position) {
        return new PresenceMessage("cursor-update", documentId, userId, null, null, position, null);
    }
}
