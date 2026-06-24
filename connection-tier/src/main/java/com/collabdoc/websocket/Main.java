package com.collabdoc.websocket;

import com.collabdoc.websocket.grpc.DocumentServiceClient;

public final class Main {

    private static final int PORT = 8081;

    private Main() {
    }

    public static void main(String[] args) {
        // Phase 6: document-service is now a separate process, reached over
        // gRPC instead of the in-process DocumentSequencer/PostgresOperationLog
        // wiring this used to own directly (that wiring now lives entirely in
        // document-service's own Main).
        String documentServiceHost = System.getenv().getOrDefault("DOCUMENT_SERVICE_HOST", "localhost");
        int documentServicePort = Integer.parseInt(System.getenv().getOrDefault("DOCUMENT_SERVICE_PORT", "9090"));

        DocumentServiceClient documentService = new DocumentServiceClient(documentServiceHost, documentServicePort);
        ConnectionTierServer server = new ConnectionTierServer(PORT, documentService);
        server.start();
    }
}
