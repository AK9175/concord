package com.collabdoc.websocket;

/** A presence entry as sent over the wire: identity plus their last-known cursor position (CP 4.4), if any. */
public record PresenceUser(String userId, String username, String color, Integer cursorPosition) {
}
