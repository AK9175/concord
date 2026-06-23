package com.collabdoc.metadata;

import java.util.List;
import java.util.Optional;

/**
 * Document id/title CRUD. In-memory for now (InMemoryDocumentMetadataStore);
 * interface designed the same way OperationLog was, so Phase 5 (CP 5.4) can
 * swap in a persistent (Postgres/H2) implementation with no change to the
 * REST layer that depends on it.
 */
public interface DocumentMetadataStore {

    Document create(String title);

    Optional<Document> get(String documentId);

    List<Document> list();

    /** Returns the renamed document, or empty if documentId doesn't exist. */
    Optional<Document> rename(String documentId, String newTitle);
}
