package com.collabdoc.metadata;

class InMemoryDocumentRosterStoreTest extends DocumentRosterStoreContractTest {

    @Override
    protected DocumentRosterStore createStore() {
        return new InMemoryDocumentRosterStore();
    }
}
