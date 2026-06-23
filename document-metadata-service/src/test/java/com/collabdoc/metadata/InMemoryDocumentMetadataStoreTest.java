package com.collabdoc.metadata;

class InMemoryDocumentMetadataStoreTest extends DocumentMetadataStoreContractTest {

    @Override
    protected DocumentMetadataStore createStore() {
        return new InMemoryDocumentMetadataStore();
    }
}
