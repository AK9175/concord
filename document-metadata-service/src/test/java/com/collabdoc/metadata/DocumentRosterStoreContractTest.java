package com.collabdoc.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared contract for any DocumentRosterStore -- in-memory or Postgres-
 * backed -- run identically against both implementations.
 */
abstract class DocumentRosterStoreContractTest {

    protected abstract DocumentRosterStore createStore();

    private String freshDocumentId() {
        return "doc-" + UUID.randomUUID();
    }

    @Test
    void addUserAssignsAUserIdAndStoresUsernameAndColor() {
        DocumentRosterStore store = createStore();
        String documentId = freshDocumentId();

        User added = store.addUser(documentId, "alice", "#ff0000");

        assertTrue(added.userId() != null && !added.userId().isBlank());
        assertEquals("alice", added.username());
        assertEquals("#ff0000", added.color());
        assertEquals(List.of(added), store.listUsers(documentId));
    }

    @Test
    void unknownDocumentHasAnEmptyRoster() {
        DocumentRosterStore store = createStore();
        assertEquals(List.of(), store.listUsers(freshDocumentId()));
    }

    @Test
    void rostersAreIndependentPerDocument() {
        DocumentRosterStore store = createStore();
        String docA = freshDocumentId();
        String docB = freshDocumentId();

        store.addUser(docA, "alice", "#ff0000");

        assertEquals(1, store.listUsers(docA).size());
        assertEquals(0, store.listUsers(docB).size());
    }

    @Test
    void renameUserKeepsUserIdButUpdatesUsernameAndColor() {
        DocumentRosterStore store = createStore();
        String documentId = freshDocumentId();
        User added = store.addUser(documentId, "alice", "#ff0000");

        User renamed = store.renameUser(documentId, added.userId(), "alicia", "#00ff00").orElseThrow();
        assertEquals(added.userId(), renamed.userId());
        assertEquals("alicia", renamed.username());
        assertEquals("#00ff00", renamed.color());
        assertEquals(List.of(renamed), store.listUsers(documentId));
    }

    @Test
    void renamingAnUnknownUserIsEmpty() {
        DocumentRosterStore store = createStore();
        String documentId = freshDocumentId();
        store.addUser(documentId, "alice", "#ff0000");

        assertEquals(Optional.empty(), store.renameUser(documentId, "does-not-exist", "x", "#000000"));
    }

    @Test
    void deleteRosterRemovesEveryEntryForThatDocumentOnly() {
        DocumentRosterStore store = createStore();
        String docToDelete = freshDocumentId();
        String otherDoc = freshDocumentId();
        store.addUser(docToDelete, "alice", "#ff0000");
        store.addUser(otherDoc, "bob", "#00ff00");

        store.deleteRoster(docToDelete);

        assertEquals(List.of(), store.listUsers(docToDelete));
        assertEquals(1, store.listUsers(otherDoc).size(), "deleting one document's roster must not affect another's");
    }

    @Test
    void deletingAnUnknownDocumentsRosterIsANoOp() {
        DocumentRosterStore store = createStore();
        store.deleteRoster(freshDocumentId());
    }
}
