package com.collabdoc.metadata;

import java.io.IOException;

public final class Main {

    private static final int PORT = 8083;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        DocumentMetadataServer server = new DocumentMetadataServer(
                PORT, new InMemoryDocumentMetadataStore(), new InMemoryDocumentRosterStore());
        server.start();
    }
}
