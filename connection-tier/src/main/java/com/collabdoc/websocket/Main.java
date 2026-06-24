package com.collabdoc.websocket;

import com.collabdoc.websocket.grpc.DocumentServiceClient;
import io.grpc.Server;
import io.grpc.ServerBuilder;

public final class Main {

    private static final int PORT = 8081;
    private static final int ADMIN_PORT = 8091;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        // Phase 6: document-service is now a separate process, reached over
        // gRPC instead of the in-process DocumentSequencer/PostgresOperationLog
        // wiring this used to own directly (that wiring now lives entirely in
        // document-service's own Main).
        String documentServiceHost = System.getenv().getOrDefault("DOCUMENT_SERVICE_HOST", "localhost");
        int documentServicePort = Integer.parseInt(System.getenv().getOrDefault("DOCUMENT_SERVICE_PORT", "9090"));

        DocumentServiceClient documentService = new DocumentServiceClient(documentServiceHost, documentServicePort);
        ConnectionTierServer server = new ConnectionTierServer(PORT, documentService);
        server.start();

        // A second, small gRPC server (separate from the WebSocket one above)
        // purely for document-metadata-service to call when a document gets
        // deleted, so anyone actively editing it gets disconnected rather than
        // left talking to a document that no longer exists anywhere.
        Server adminServer = ServerBuilder.forPort(ADMIN_PORT)
                .addService(new ConnectionTierAdminServer(server.sessions()))
                .build()
                .start();
        System.out.println("connection-tier: admin gRPC listening on port " + ADMIN_PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(adminServer::shutdown));
        adminServer.awaitTermination();
    }
}
