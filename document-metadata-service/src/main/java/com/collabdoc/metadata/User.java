package com.collabdoc.metadata;

/**
 * An entry in a document's roster: a userId the server assigned, plus the
 * display identity the client chose. No password, no session -- the server
 * trusts whatever identity is presented (see the project's identity model:
 * this is identity, not authentication).
 */
public record User(String userId, String username, String color) {
}
