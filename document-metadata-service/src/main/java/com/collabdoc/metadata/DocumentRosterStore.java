package com.collabdoc.metadata;

import java.util.List;
import java.util.Optional;

/**
 * Each document's own userId -> {username, color} roster. Per-document, not
 * global -- the same browser could appear under different identities on
 * different documents. In-memory for now; persists alongside metadata in
 * Phase 5 (CP 5.4).
 */
public interface DocumentRosterStore {

    /** Adds a new identity to documentId's roster, assigning a fresh userId. */
    User addUser(String documentId, String username, String color);

    List<User> listUsers(String documentId);

    /**
     * Updates an existing identity's display name/color without disturbing
     * its userId -- ops/cursors already attributed to that userId stay
     * attributed to the same identity, just with a new name going forward.
     * Returns empty if userId isn't in documentId's roster.
     */
    Optional<User> renameUser(String documentId, String userId, String username, String color);
}
