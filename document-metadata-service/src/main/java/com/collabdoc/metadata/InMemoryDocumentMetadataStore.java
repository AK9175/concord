package com.collabdoc.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryDocumentMetadataStore implements DocumentMetadataStore {

    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    @Override
    public Document create(String title) {
        String id = UUID.randomUUID().toString();
        Document document = new Document(id, title);
        documents.put(id, document);
        return document;
    }

    @Override
    public Optional<Document> get(String documentId) {
        return Optional.ofNullable(documents.get(documentId));
    }

    @Override
    public List<Document> list() {
        return new ArrayList<>(documents.values());
    }

    @Override
    public Optional<Document> rename(String documentId, String newTitle) {
        Document existing = documents.get(documentId);
        if (existing == null) {
            return Optional.empty();
        }
        Document renamed = new Document(existing.id(), newTitle);
        documents.put(documentId, renamed);
        return Optional.of(renamed);
    }

    @Override
    public boolean delete(String documentId) {
        return documents.remove(documentId) != null;
    }
}
