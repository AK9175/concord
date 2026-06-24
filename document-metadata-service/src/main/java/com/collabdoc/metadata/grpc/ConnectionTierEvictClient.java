package com.collabdoc.metadata.grpc;

import com.collabdoc.connectiontier.proto.ConnectionTierAdminGrpc;
import com.collabdoc.connectiontier.proto.EvictDocumentRequest;
import com.collabdoc.connectiontier.proto.EvictDocumentResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

/**
 * The half of delete orchestration that disconnects anyone actively editing
 * a document that's about to be deleted: a thin gRPC client to connection-
 * tier's small admin service. Best-effort by design (see
 * DocumentMetadataServer.handleSingleDocument's DELETE handling) -- a
 * temporarily-unreachable connection-tier shouldn't block deleting a
 * document whose actual content nobody may even be looking at right now.
 */
public class ConnectionTierEvictClient {

    private final ManagedChannel channel;
    private final ConnectionTierAdminGrpc.ConnectionTierAdminBlockingStub stub;

    public ConnectionTierEvictClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    /** Package-visible for tests, which build a channel over an in-process transport rather than a real socket. */
    ConnectionTierEvictClient(ManagedChannel channel) {
        this.channel = channel;
        this.stub = ConnectionTierAdminGrpc.newBlockingStub(channel);
    }

    /** Number of connections actually closed, purely informational. */
    public int evictDocument(String documentId) {
        EvictDocumentResponse response =
                stub.evictDocument(EvictDocumentRequest.newBuilder().setDocumentId(documentId).build());
        return response.getConnectionsClosed();
    }

    public void shutdown() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
