package com.collabdoc.websocket.grpc;

import com.collabdoc.document.DocumentSequencer;
import com.collabdoc.document.OperationLog;
import com.collabdoc.document.grpc.DocumentServiceGrpcImpl;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

/**
 * Test-only stand-in for a real, separately-running document-service
 * process: a real DocumentServiceGrpcImpl/DocumentSequencer pair, served
 * over grpc-java's in-process transport (no real sockets, but a genuine
 * client/server/serialization round trip) so ConnectionTierServer tests
 * exercise the actual gRPC-shaped path, not a hand-rolled mock of
 * DocumentServiceClient.
 */
public class InProcessDocumentService implements AutoCloseable {

    private final Server server;
    private final ManagedChannel channel;
    private final DocumentServiceClient client;

    public InProcessDocumentService(OperationLog operationLog) {
        String serverName = InProcessServerBuilder.generateName();
        DocumentSequencer sequencer = new DocumentSequencer(operationLog);

        try {
            this.server = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new DocumentServiceGrpcImpl(sequencer))
                    .build()
                    .start();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("failed to start in-process document-service", ex);
        }

        this.channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        this.client = new DocumentServiceClient(channel);
    }

    public DocumentServiceClient client() {
        return client;
    }

    @Override
    public void close() {
        channel.shutdownNow();
        server.shutdownNow();
    }
}
