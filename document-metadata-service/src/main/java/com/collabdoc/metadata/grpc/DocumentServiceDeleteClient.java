package com.collabdoc.metadata.grpc;

import com.collabdoc.documentservice.proto.DeleteDocumentRequest;
import com.collabdoc.documentservice.proto.DocumentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.TimeUnit;

/**
 * The half of delete orchestration that wipes a document's actual op log:
 * a thin gRPC client to document-service's DeleteDocument RPC. Blocking,
 * not CompletableFuture-based like connection-tier's DocumentServiceClient
 * -- DocumentMetadataServer's request handling is already synchronous
 * (plain JDBC calls via metadataStore/rosterStore), so there's no async
 * pipeline here to bridge into.
 */
public class DocumentServiceDeleteClient {

    private final ManagedChannel channel;
    private final DocumentServiceGrpc.DocumentServiceBlockingStub stub;

    public DocumentServiceDeleteClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    /** Package-visible for tests, which build a channel over an in-process transport rather than a real socket. */
    DocumentServiceDeleteClient(ManagedChannel channel) {
        this.channel = channel;
        this.stub = DocumentServiceGrpc.newBlockingStub(channel);
    }

    public void deleteDocument(String documentId) {
        stub.deleteDocument(DeleteDocumentRequest.newBuilder().setDocumentId(documentId).build());
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
