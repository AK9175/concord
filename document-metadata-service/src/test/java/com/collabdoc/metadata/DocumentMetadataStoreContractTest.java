package com.collabdoc.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared contract for any DocumentMetadataStore -- in-memory or Postgres-
 * backed -- run identically against both implementations, the same pattern
 * used for OperationLogContractTest in CP 5.1.
 */
abstract class DocumentMetadataStoreContractTest {

    protected abstract DocumentMetadataStore createStore();

    @Test
    void createAssignsAnIdAndStoresTheTitle() {
        DocumentMetadataStore store = createStore();
        Document created = store.create("My Notes");

        assertTrue(created.id() != null && !created.id().isBlank());
        assertEquals("My Notes", created.title());
        assertEquals(created, store.get(created.id()).orElseThrow());
    }

    @Test
    void unknownDocumentIsEmpty() {
        DocumentMetadataStore store = createStore();
        assertEquals(Optional.empty(), store.get("does-not-exist"));
    }

    @Test
    void listReturnsAllCreatedDocuments() {
        DocumentMetadataStore store = createStore();
        Document a = store.create("A");
        Document b = store.create("B");

        List<Document> all = store.list();
        assertEquals(2, all.size());
        assertTrue(all.contains(a));
        assertTrue(all.contains(b));
    }

    @Test
    void renameUpdatesTheTitleButKeepsTheId() {
        DocumentMetadataStore store = createStore();
        Document created = store.create("Original");

        Document renamed = store.rename(created.id(), "Renamed").orElseThrow();
        assertEquals(created.id(), renamed.id());
        assertEquals("Renamed", renamed.title());
        assertEquals("Renamed", store.get(created.id()).orElseThrow().title());
    }

    @Test
    void renamingAnUnknownDocumentIsEmpty() {
        DocumentMetadataStore store = createStore();
        assertEquals(Optional.empty(), store.rename("does-not-exist", "x"));
    }

    @Test
    void deleteRemovesTheDocumentAndReturnsTrue() {
        DocumentMetadataStore store = createStore();
        Document created = store.create("Throwaway");

        assertTrue(store.delete(created.id()));
        assertEquals(Optional.empty(), store.get(created.id()));
    }

    @Test
    void deletingAnUnknownDocumentReturnsFalse() {
        DocumentMetadataStore store = createStore();
        assertEquals(false, store.delete("does-not-exist"));
    }
}
