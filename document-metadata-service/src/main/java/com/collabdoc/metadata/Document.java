package com.collabdoc.metadata;

/** A document's metadata: just enough to create, name, and find it. No content here -- that's document-service. */
public record Document(String id, String title) {
}
